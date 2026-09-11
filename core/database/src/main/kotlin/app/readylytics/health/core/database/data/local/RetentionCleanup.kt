package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import java.time.Instant
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
                val sleepDel = daos.sleepSessionDao.deleteBeforeTimestamp(cutoffMs)
                val bucketDel = daos.minuteBucketMaintenanceDao.deleteBeforeTimestamp(cutoffMs)
                val workoutDel = daos.workoutDao.deleteBeforeTimestamp(cutoffMs)
                val summaryDel = dailySummaryDao.deleteBeforeTimestamp(cutoffMs)
                val weightDel = daos.weightRecordDao.deleteBeforeTimestamp(cutoffMs)
                val fatDel = daos.bodyFatRecordDao.deleteBeforeTimestamp(cutoffMs)
                val bpDel = daos.bloodPressureRecordDao.deleteBeforeTimestamp(cutoffMs)
                val oxyDel = daos.oxygenSaturationRecordDao.deleteBeforeTimestamp(cutoffMs)
                val tempDel = daos.bodyTemperatureRecordDao.deleteBeforeTimestamp(cutoffMs)
                val stepDel = daos.stepRecordDao.deleteBeforeTimestamp(cutoffMs)
                val vo2Del = vo2MaxRecordDao.deleteBefore(cutoffMs)

                val lowVolumeDeleted =
                    sleepDel + bucketDel + workoutDel + summaryDel + weightDel +
                        fatDel + bpDel + oxyDel + tempDel + stepDel + vo2Del
                totalDeleted += lowVolumeDeleted

                if (totalDeleted > 0 && dirtyRangeDao != null && healthMutationStateDao != null) {
                    val effectiveEarliest = earliestMs ?: (cutoffMs - DAY_MS)
                    val startDate = Instant.ofEpochMilli(effectiveEarliest).atZone(ZoneOffset.UTC).toLocalDate()
                    val today = Instant.ofEpochMilli(cutoffMs).atZone(ZoneOffset.UTC).toLocalDate()
                    val endInclusive = maxOf(today, Instant.ofEpochMilli(cutoffMs).atZone(ZoneOffset.UTC).toLocalDate())

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
            }

            if (totalDeleted == 0) return null
            val effectiveEarliest = earliestMs ?: (cutoffMs - DAY_MS)
            return ScoreInvalidation.AffectedRange(
                start = Instant.ofEpochMilli(effectiveEarliest).atZone(ZoneOffset.UTC).toLocalDate(),
                endInclusive = Instant.ofEpochMilli(cutoffMs).atZone(ZoneOffset.UTC).toLocalDate(),
            )
        }

        private suspend fun deleteInBatches(deleteBatch: suspend (limit: Int) -> Int) {
            while (true) {
                val deleted = transactionRunner.runInTransaction { deleteBatch(BATCH_SIZE) }
                if (deleted < BATCH_SIZE) break
            }
        }

        private companion object {
            private const val BATCH_SIZE = 10_000
            private const val DAY_MS = 86_400_000L
        }
    }
