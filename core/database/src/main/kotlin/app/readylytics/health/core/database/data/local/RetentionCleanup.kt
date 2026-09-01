package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.model.domain.repository.TransactionRunner
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
    ) {
        // DB-002: heart_rate_records/hrv_records are deleted in bounded batches, each its own
        // transaction, before the remaining nine low-volume tables run in a single transaction as
        // before. Every constituent delete is `WHERE timestampMs < cutoff`, so re-running after a
        // killed worker (mid-batch, or between the HR/HRV phase and the low-volume phase) is safe
        // -- already-deleted rows simply contribute 0 to the next call.
        //
        // R2-CACHE-001: returns the ScoreInvalidation.AffectedRange this call actually touched, or
        // `null` on a no-op run (nothing older than cutoffMs). Scoped to heart_rate_records and
        // hr_minute_buckets -- the two sources that feed the scoring walk-forward and change size
        // with the rolling retention cutoff -- read *before* they're deleted. Sleep/workout/vitals
        // retention deletions ride the same daily cutoff advance and are covered by the same
        // 84-day forward slack, so they are not separately tracked here.
        suspend fun deleteBefore(cutoffMs: Long): ScoreInvalidation.AffectedRange? {
            val earliestHrMs = daos.heartRateDao.minTimestampBefore(cutoffMs)
            val earliestBucketMs = daos.minuteBucketMaintenanceDao.minBucketStartBefore(cutoffMs)
            val earliestMs = listOfNotNull(earliestHrMs, earliestBucketMs).minOrNull()

            deleteInBatches { limit -> daos.heartRateDao.deleteBeforeTimestampBatch(cutoffMs, limit) }
            deleteInBatches { limit -> daos.hrvDao.deleteBeforeTimestampBatch(cutoffMs, limit) }

            transactionRunner.runInTransaction {
                daos.sleepSessionDao.deleteBeforeTimestamp(cutoffMs)
                daos.minuteBucketMaintenanceDao.deleteBeforeTimestamp(cutoffMs)
                daos.workoutDao.deleteBeforeTimestamp(cutoffMs)
                dailySummaryDao.deleteBeforeTimestamp(cutoffMs)
                daos.weightRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.bodyFatRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.bloodPressureRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.oxygenSaturationRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.bodyTemperatureRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.stepRecordDao.deleteBeforeTimestamp(cutoffMs)
            }

            val earliest = earliestMs ?: return null
            return ScoreInvalidation.AffectedRange(
                start = Instant.ofEpochMilli(earliest).atZone(ZoneOffset.UTC).toLocalDate(),
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
        }
    }
