package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateRefreshStagingDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketMaintenanceDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageMaintenanceDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageSelectionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** DAOs for ingested source records, minute buckets and minute coverage/refresh staging. */
@Module
@InstallIn(SingletonComponent::class)
object SourceDaoModule {
    @Provides
    fun provideSourceRecordDao(db: HealthDatabase): SourceRecordDao = db.sourceRecordDao()

    @Provides
    fun provideMinuteBucketDao(db: HealthDatabase): MinuteBucketDao = db.minuteBucketDao()

    @Provides
    fun provideMinuteBucketMaintenanceDao(db: HealthDatabase): MinuteBucketMaintenanceDao =
        db.minuteBucketMaintenanceDao()

    @Provides
    fun provideMinuteCoverageDao(db: HealthDatabase): MinuteCoverageDao = db.minuteCoverageDao()

    @Provides
    fun provideMinuteCoverageMaintenanceDao(db: HealthDatabase): MinuteCoverageMaintenanceDao =
        db.minuteCoverageMaintenanceDao()

    @Provides
    fun provideMinuteCoverageSelectionDao(db: HealthDatabase): MinuteCoverageSelectionDao =
        db.minuteCoverageSelectionDao()

    @Provides
    fun provideHeartRateRefreshStagingDao(db: HealthDatabase): HeartRateRefreshStagingDao =
        db.heartRateRefreshStagingDao()
}
