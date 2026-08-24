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
        suspend fun deleteBefore(cutoffMs: Long) =
            transactionRunner.runInTransaction {
                daos.sleepSessionDao.deleteBeforeTimestamp(cutoffMs)
                daos.heartRateDao.deleteBeforeTimestamp(cutoffMs)
                daos.hrvDao.deleteBeforeTimestamp(cutoffMs)
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
