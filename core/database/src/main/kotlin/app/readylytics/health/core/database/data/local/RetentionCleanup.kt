package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.model.domain.repository.TransactionRunner
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
        suspend fun deleteBefore(cutoffMs: Long) {
            deleteInBatches { limit -> daos.heartRateDao.deleteBeforeTimestampBatch(cutoffMs, limit) }
            deleteInBatches { limit -> daos.hrvDao.deleteBeforeTimestampBatch(cutoffMs, limit) }

            transactionRunner.runInTransaction {
                daos.sleepSessionDao.deleteBeforeTimestamp(cutoffMs)
                daos.minuteBucketDao.deleteBeforeTimestamp(cutoffMs)
                daos.workoutDao.deleteBeforeTimestamp(cutoffMs)
                dailySummaryDao.deleteBeforeTimestamp(cutoffMs)
                daos.weightRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.bodyFatRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.bloodPressureRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.oxygenSaturationRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.bodyTemperatureRecordDao.deleteBeforeTimestamp(cutoffMs)
                daos.stepRecordDao.deleteBeforeTimestamp(cutoffMs)
            }
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
