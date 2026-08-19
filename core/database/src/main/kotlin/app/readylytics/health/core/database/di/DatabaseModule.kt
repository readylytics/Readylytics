package app.readylytics.health.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.readylytics.health.data.local.DatabaseMigrations
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.RoomTransactionRunner
import app.readylytics.health.data.local.RoomWalDiagnostics
import app.readylytics.health.core.database.data.local.dao.AuditEventDao
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.InsightDismissalDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.StepRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.core.database.data.security.KeyProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.repository.WalDiagnostics
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {
    @Binds
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds
    abstract fun bindWalDiagnostics(impl: RoomWalDiagnostics): WalDiagnostics

    @Binds
    abstract fun bindKeyProvider(impl: AndroidKeystoreKeyProvider): KeyProvider

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(
            @ApplicationContext context: Context,
            sqlCipherKeyManager: SqlCipherKeyManager,
            databaseReadinessGate: DatabaseReadinessGate,
        ): HealthDatabase {
            val dbFile = context.getDatabasePath("health_dashboard.db")
            sqlCipherKeyManager.migrateIfNeeded(dbFile)
            requireDatabaseReady(databaseReadinessGate)

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
                                db.execSQL("PRAGMA foreign_keys = ON")
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
    }
}

fun requireDatabaseReady(databaseReadinessGate: DatabaseReadinessGate) {
    check(databaseReadinessGate.inspect() == DatabaseReadiness.Ready) {
        "HealthDatabase cannot open before the external v7 migration is complete"
    }
}
