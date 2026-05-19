package com.gregor.lauritz.healthdashboard.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gregor.lauritz.healthdashboard.data.local.DatabaseMigrations
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.security.SqlCipherKeyManager
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
        sqlCipherKeyManager: SqlCipherKeyManager,
    ): HealthDatabase {
        val dbFile = context.getDatabasePath("health_dashboard.db")
        sqlCipherKeyManager.migrateIfNeeded(dbFile)

        val builder =
            Room
                .databaseBuilder<HealthDatabase>(context, "health_dashboard.db")
                .openHelperFactory(sqlCipherKeyManager.getOrCreateFactory(dbFile))
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*DatabaseMigrations.all)
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA synchronous = NORMAL")
                        }
                    },
                )

        return builder.build()
    }

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
    fun provideDailySummaryDao(db: HealthDatabase): DailySummaryDao = db.dailySummaryDao()
}
