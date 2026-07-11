package app.readylytics.health.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.readylytics.health.BuildConfig
import app.readylytics.health.data.local.DatabaseMigrations
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.dao.AuditEventDao
import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.InsightDismissalDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.SleepStageDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.data.security.SqlCipherKeyManager
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
                .apply { if (BuildConfig.DEBUG) fallbackToDestructiveMigration(dropAllTables = true) }
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*DatabaseMigrations.all)
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA synchronous = NORMAL")
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }
                    },
                )

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideTransactionRunner(db: HealthDatabase): app.readylytics.health.domain.repository.TransactionRunner =
        app.readylytics.health.data.local
            .RoomTransactionRunner(db)

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

    @Provides
    fun provideBloodPressureRecordDao(db: HealthDatabase): BloodPressureRecordDao = db.bloodPressureRecordDao()

    @Provides
    fun provideOxygenSaturationRecordDao(db: HealthDatabase): OxygenSaturationRecordDao = db.oxygenSaturationRecordDao()

    @Provides
    fun provideInsightDismissalDao(db: HealthDatabase): InsightDismissalDao = db.insightDismissalDao()

    @Provides
    fun provideAuditEventDao(db: HealthDatabase): AuditEventDao = db.auditEventDao()
}
