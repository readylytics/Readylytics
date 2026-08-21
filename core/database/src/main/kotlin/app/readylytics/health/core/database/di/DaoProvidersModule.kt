package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DaoProvidersModule {
    @Provides
    fun provideSleepSessionDao(db: HealthDatabase): SleepSessionDao = db.sleepSessionDao()

    @Provides
    fun provideSleepStageDao(db: HealthDatabase): SleepStageDao = db.sleepStageDao()

    @Provides
    fun provideHeartRateDao(db: HealthDatabase): HeartRateDao = db.heartRateDao()

    @Provides
    fun provideHrvDao(db: HealthDatabase): HrvDao = db.hrvDao()

    @Provides
    fun provideWorkoutDao(db: HealthDatabase): WorkoutDao = db.workoutDao()

    @Provides
    fun provideWorkoutRoutePointDao(db: HealthDatabase): WorkoutRoutePointDao = db.workoutRoutePointDao()

    @Provides
    fun provideDailySummaryDao(db: HealthDatabase): DailySummaryDao = db.dailySummaryDao()

    @Provides
    fun provideWeightRecordDao(db: HealthDatabase): WeightRecordDao = db.weightRecordDao()

    @Provides
    fun provideBodyFatRecordDao(db: HealthDatabase): BodyFatRecordDao = db.bodyFatRecordDao()
}
