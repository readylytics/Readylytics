package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.dao.AuditEventDao
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.InsightDismissalDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketMaintenanceDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DaoProvidersSupplementModule {
    @Provides
    fun provideBloodPressureRecordDao(db: HealthDatabase): BloodPressureRecordDao = db.bloodPressureRecordDao()

    @Provides
    fun provideOxygenSaturationRecordDao(db: HealthDatabase): OxygenSaturationRecordDao =
        db.oxygenSaturationRecordDao()

    @Provides
    fun provideBodyTemperatureRecordDao(db: HealthDatabase): BodyTemperatureRecordDao =
        db.bodyTemperatureRecordDao()

    @Provides
    fun provideInsightDismissalDao(db: HealthDatabase): InsightDismissalDao = db.insightDismissalDao()

    @Provides
    fun provideAuditEventDao(db: HealthDatabase): AuditEventDao = db.auditEventDao()

    @Provides
    fun provideStepRecordDao(db: HealthDatabase): StepRecordDao = db.stepRecordDao()

    @Provides
    fun provideSourceRecordDao(db: HealthDatabase): SourceRecordDao = db.sourceRecordDao()

    @Provides
    fun provideMinuteBucketDao(db: HealthDatabase): MinuteBucketDao = db.minuteBucketDao()

    @Provides
    fun provideMinuteBucketMaintenanceDao(db: HealthDatabase): MinuteBucketMaintenanceDao =
        db.minuteBucketMaintenanceDao()

    @Provides
    fun provideVo2MaxRecordDao(db: HealthDatabase): Vo2MaxRecordDao = db.vo2MaxRecordDao()
}
