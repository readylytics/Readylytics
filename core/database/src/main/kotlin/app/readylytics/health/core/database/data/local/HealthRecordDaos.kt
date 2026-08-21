package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import javax.inject.Inject

data class HealthRecordDaos
    @Inject
    constructor(
        val sleepSessionDao: SleepSessionDao,
        val sleepStageDao: SleepStageDao,
        val heartRateDao: HeartRateDao,
        val hrvDao: HrvDao,
        val workoutDao: WorkoutDao,
        val workoutRoutePointDao: WorkoutRoutePointDao,
        val weightRecordDao: WeightRecordDao,
        val bodyFatRecordDao: BodyFatRecordDao,
        val bloodPressureRecordDao: BloodPressureRecordDao,
        val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        val bodyTemperatureRecordDao: BodyTemperatureRecordDao,
        val stepRecordDao: StepRecordDao,
        val sourceRecordDao: SourceRecordDao,
        val minuteBucketDao: MinuteBucketDao,
    )
