package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** DAOs for physiological samples and body measurements. */
@Module
@InstallIn(SingletonComponent::class)
object VitalsDaoModule {
    @Provides
    fun provideHeartRateDao(db: HealthDatabase): HeartRateDao = db.heartRateDao()

    @Provides
    fun provideHrvDao(db: HealthDatabase): HrvDao = db.hrvDao()

    @Provides
    fun provideWeightRecordDao(db: HealthDatabase): WeightRecordDao = db.weightRecordDao()

    @Provides
    fun provideBodyFatRecordDao(db: HealthDatabase): BodyFatRecordDao = db.bodyFatRecordDao()

    @Provides
    fun provideBloodPressureRecordDao(db: HealthDatabase): BloodPressureRecordDao = db.bloodPressureRecordDao()

    @Provides
    fun provideOxygenSaturationRecordDao(db: HealthDatabase): OxygenSaturationRecordDao = db.oxygenSaturationRecordDao()

    @Provides
    fun provideBodyTemperatureRecordDao(db: HealthDatabase): BodyTemperatureRecordDao = db.bodyTemperatureRecordDao()

    @Provides
    fun provideVo2MaxRecordDao(db: HealthDatabase): Vo2MaxRecordDao = db.vo2MaxRecordDao()

    @Provides
    fun provideStepRecordDao(db: HealthDatabase): StepRecordDao = db.stepRecordDao()
}
