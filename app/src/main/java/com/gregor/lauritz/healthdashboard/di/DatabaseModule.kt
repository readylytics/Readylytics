package com.gregor.lauritz.healthdashboard.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): HealthDatabase =
        Room
            .databaseBuilder<HealthDatabase>(context, "health_dashboard.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(
                HealthDatabase.MIGRATION_1_2,
                HealthDatabase.MIGRATION_2_3,
                HealthDatabase.MIGRATION_3_4,
                HealthDatabase.MIGRATION_4_5,
                HealthDatabase.MIGRATION_5_6,
                HealthDatabase.MIGRATION_6_7,
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideSleepSessionDao(db: HealthDatabase): SleepSessionDao = db.sleepSessionDao()

    @Provides
    fun provideHeartRateDao(db: HealthDatabase): HeartRateDao = db.heartRateDao()

    @Provides
    fun provideHrvDao(db: HealthDatabase): HrvDao = db.hrvDao()

    @Provides
    fun provideWorkoutDao(db: HealthDatabase): WorkoutDao = db.workoutDao()

    @Provides
    fun provideDailySummaryDao(db: HealthDatabase): DailySummaryDao = db.dailySummaryDao()
}
