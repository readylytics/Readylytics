package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** DAOs for sleep sessions and workouts. */
@Module
@InstallIn(SingletonComponent::class)
object ActivityDaoModule {
    @Provides
    fun provideSleepSessionDao(db: HealthDatabase): SleepSessionDao = db.sleepSessionDao()

    @Provides
    fun provideSleepStageDao(db: HealthDatabase): SleepStageDao = db.sleepStageDao()

    @Provides
    fun provideWorkoutDao(db: HealthDatabase): WorkoutDao = db.workoutDao()

    @Provides
    fun provideWorkoutRoutePointDao(db: HealthDatabase): WorkoutRoutePointDao = db.workoutRoutePointDao()
}
