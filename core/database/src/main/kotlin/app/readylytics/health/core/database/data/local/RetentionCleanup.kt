package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.logI
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes every health row older than the retention cutoff. Deliberately does NOT journal dirty
 * work or bump the source generation: data aging out of the retention window never invalidates a
 * retained `daily_summaries` row -- each was scored (and its baselines frozen) while the older data
 * still existed. Journaling here used to re-run an ~84-day recompute every night the cutoff
 * advanced. [deleteBefore]'s returned range is informational (logging) only.
 */
@Singleton
class RetentionCleanup
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val daos: HealthRecordDaos,
        private val dailySummaryDao: DailySummaryDao,
        private val vo2MaxRecordDao: Vo2MaxRecordDao,
        private val coordinator: HealthMutationCoordinator,
    ) {
        suspend fun deleteBefore(
            cutoffMs: Long,
            runContext: ScoringRunContext,
        ): ScoreInvalidation.AffectedRange? {
            return coordinator.withMutation { doDeleteBefore(cutoffMs, runContext) }
        }

        private suspend fun doDeleteBefore(
            cutoffMs: Long,
            runContext: ScoringRunContext,
        ): ScoreInvalidation.AffectedRange? {
            val earliestHrMs = daos.heartRateDao.minTimestampBefore(cutoffMs)
            val earliestBucketMs = daos.minuteBucketMaintenanceDao.minBucketStartBefore(cutoffMs)
            val earliestMs = listOfNotNull(earliestHrMs, earliestBucketMs).minOrNull()
            var totalDeleted = 0

            deleteInBatches { limit ->
                val count = daos.heartRateDao.deleteBeforeTimestampBatch(cutoffMs, limit)
                totalDeleted += count
                count
            }
            deleteInBatches { limit ->
                val count = daos.hrvDao.deleteBeforeTimestampBatch(cutoffMs, limit)
                totalDeleted += count
                count
            }

            transactionRunner.runInTransaction {
                totalDeleted += deleteLowVolumeTables(cutoffMs)
            }

            // PERF-003: must run after every raw-row deletion batch above has committed, never
            // before -- a source row whose only children were just deleted this run has to be
            // judged against that post-deletion state. Metadata-only, so it neither contributes to
            // totalDeleted nor extends the returned affected range.
            val gcDeleted = SourceMetadataGc.collect(daos.sourceRecordDao)
            if (gcDeleted > 0) {
                logI(TAG) { "Collected $gcDeleted unreferenced source-metadata rows" }
            }

            if (totalDeleted == 0) return null
            val effectiveEarliest = earliestMs ?: (cutoffMs - DAY_MS)
            return ScoreInvalidation.AffectedRange(
                start = Instant.ofEpochMilli(effectiveEarliest).atZone(runContext.zoneId).toLocalDate(),
                endInclusive = Instant.ofEpochMilli(cutoffMs).atZone(runContext.zoneId).toLocalDate(),
            )
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
