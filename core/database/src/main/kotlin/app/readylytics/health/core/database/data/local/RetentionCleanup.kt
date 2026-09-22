package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetentionCleanup
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val daos: HealthRecordDaos,
        private val dailySummaryDao: DailySummaryDao,
        private val vo2MaxRecordDao: Vo2MaxRecordDao,
        private val coordinator: HealthMutationCoordinator? = null,
        private val dirtyRangeDao: DirtyRangeDao? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val settingsRepository: SettingsRepository? = null,
    ) {
        suspend fun deleteBefore(cutoffMs: Long): ScoreInvalidation.AffectedRange? {
            val runner: suspend () -> ScoreInvalidation.AffectedRange? = { doDeleteBefore(cutoffMs) }
            return if (coordinator != null) {
                coordinator.withMutation { runner() }
            } else {
                runner()
            }
        }

        private suspend fun doDeleteBefore(cutoffMs: Long): ScoreInvalidation.AffectedRange? {
            val earliestHrMs = daos.heartRateDao.minTimestampBefore(cutoffMs)
            val earliestBucketMs = daos.minuteBucketMaintenanceDao.minBucketStartBefore(cutoffMs)
            val earliestMs = listOfNotNull(earliestHrMs, earliestBucketMs).minOrNull()
            var totalDeleted = 0
            var dirtyRecorded = false

            suspend fun ensureJournaled() {
                if (dirtyRecorded) return
                recordDirtyRange(cutoffMs, earliestMs)
                dirtyRecorded = true
            }

            deleteInBatches { limit ->
                val count = daos.heartRateDao.deleteBeforeTimestampBatch(cutoffMs, limit)
                if (count > 0) ensureJournaled()
                totalDeleted += count
                count
            }
            deleteInBatches { limit ->
                val count = daos.hrvDao.deleteBeforeTimestampBatch(cutoffMs, limit)
                if (count > 0) ensureJournaled()
                totalDeleted += count
                count
            }

            transactionRunner.runInTransaction {
                val lowVolumeDeleted = deleteLowVolumeTables(cutoffMs)
                if (lowVolumeDeleted > 0) ensureJournaled()
                totalDeleted += lowVolumeDeleted
            }

            // PERF-003: must run after every raw-row deletion batch above has committed, never
            // before -- a source row whose only children were just deleted this run has to be
            // judged against that post-deletion state. Metadata-only, so it neither contributes to
            // totalDeleted (which gates dirty-range journaling/score invalidation) nor extends the
            // returned affected range.
            val gcDeleted = SourceMetadataGc.collect(daos.sourceRecordDao)
            if (gcDeleted > 0) {
                logI(TAG) { "Collected $gcDeleted unreferenced source-metadata rows" }
            }

            if (totalDeleted == 0) return null
            val effectiveEarliest = earliestMs ?: (cutoffMs - DAY_MS)
            return ScoreInvalidation.AffectedRange(
                start = Instant.ofEpochMilli(effectiveEarliest).atZone(ZoneOffset.UTC).toLocalDate(),
                endInclusive = Instant.ofEpochMilli(cutoffMs).atZone(ZoneOffset.UTC).toLocalDate(),
            )
        }

        private suspend fun recordDirtyRange(
            cutoffMs: Long,
            earliestMs: Long?,
        ) {
            if (dirtyRangeDao == null || healthMutationStateDao == null) return
            val effectiveEarliest = earliestMs ?: (cutoffMs - DAY_MS)
            val scoringZone = resolveScoringZone()
            val startDate = Instant.ofEpochMilli(effectiveEarliest).atZone(scoringZone).toLocalDate()
            val today = LocalDate.now(clock.withZone(scoringZone))
            val endInclusive = maxOf(today, Instant.ofEpochMilli(cutoffMs).atZone(scoringZone).toLocalDate())

            healthMutationStateDao.incrementGeneration()
            val currentGen = healthMutationStateDao.current().sourceGeneration
            dirtyRangeDao.insert(
                DirtyRangeEntity(
                    sourceGeneration = currentGen,
                    startEpochDay = startDate.toEpochDay(),
                    endEpochDayInclusive = endInclusive.toEpochDay(),
                    nextEpochDay = startDate.toEpochDay(),
                    reason = "RETENTION_CLEANUP",
                    scoringSnapshotId = "ACTIVE",
                ),
            )
        }

        private suspend fun resolveScoringZone() =
            try {
                settingsRepository?.userPreferences?.first()?.scoringZone() ?: clock.zone
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                clock.zone
            }

        private suspend fun deleteLowVolumeTables(cutoffMs: Long): Int =
            daos.sleepSessionDao.deleteBeforeTimestamp(cutoffMs) +
                daos.minuteBucketMaintenanceDao.deleteBeforeTimestamp(cutoffMs) +
                daos.minuteBucketMaintenanceDao.deleteContributionsBeforeTimestamp(cutoffMs) +
                daos.minuteBucketMaintenanceDao.deleteCoverageBeforeTimestamp(cutoffMs) +
                daos.workoutDao.deleteBeforeTimestamp(cutoffMs) +
                dailySummaryDao.deleteBeforeTimestamp(cutoffMs) +
                daos.weightRecordDao.deleteBeforeTimestamp(cutoffMs) +
                daos.bodyFatRecordDao.deleteBeforeTimestamp(cutoffMs) +
                daos.bloodPressureRecordDao.deleteBeforeTimestamp(cutoffMs) +
                daos.oxygenSaturationRecordDao.deleteBeforeTimestamp(cutoffMs) +
                daos.bodyTemperatureRecordDao.deleteBeforeTimestamp(cutoffMs) +
                daos.stepRecordDao.deleteBeforeTimestamp(cutoffMs) +
                vo2MaxRecordDao.deleteBefore(cutoffMs)

        private suspend fun deleteInBatches(deleteBatch: suspend (limit: Int) -> Int) {
            while (true) {
                val deleted = transactionRunner.runInTransaction { deleteBatch(BATCH_SIZE) }
                if (deleted < BATCH_SIZE) break
            }
        }

        private companion object {
            private const val BATCH_SIZE = 10_000
            private const val DAY_MS = 86_400_000L
            private const val TAG = "RetentionCleanup"
        }
    }
