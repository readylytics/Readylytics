package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetentionCleanup
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val sleepDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val dailySummaryDao: DailySummaryDao,
        private val weightDao: WeightRecordDao,
        private val bodyFatDao: BodyFatRecordDao,
        private val bloodPressureDao: BloodPressureRecordDao,
        private val oxygenSaturationDao: OxygenSaturationRecordDao,
        private val bodyTemperatureDao: BodyTemperatureRecordDao,
        private val stepRecordDao: StepRecordDao,
        private val minuteBucketDao: MinuteBucketDao,
    ) {
        suspend fun deleteBefore(cutoffMs: Long) =
            transactionRunner.runInTransaction {
                sleepDao.deleteBeforeTimestamp(cutoffMs)
                heartRateDao.deleteBeforeTimestamp(cutoffMs)
                hrvDao.deleteBeforeTimestamp(cutoffMs)
                minuteBucketDao.deleteBeforeTimestamp(cutoffMs)
                workoutDao.deleteBeforeTimestamp(cutoffMs)
                dailySummaryDao.deleteBeforeTimestamp(cutoffMs)
                weightDao.deleteBeforeTimestamp(cutoffMs)
                bodyFatDao.deleteBeforeTimestamp(cutoffMs)
                bloodPressureDao.deleteBeforeTimestamp(cutoffMs)
                oxygenSaturationDao.deleteBeforeTimestamp(cutoffMs)
                bodyTemperatureDao.deleteBeforeTimestamp(cutoffMs)
                stepRecordDao.deleteBeforeTimestamp(cutoffMs)
            }
    }
