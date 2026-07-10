package app.readylytics.health.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    private val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop and recreate the daily_summaries table to ensure it matches the full 76-column schema.
                // This is safe because it only acts as a computed cache; all scores are recalculated.
                db.execSQL("DROP TABLE IF EXISTS `daily_summaries`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `daily_summaries` (
                        `dateMidnightMs` INTEGER NOT NULL,
                        `sleepScore` REAL,
                        `nocturnalHrv` INTEGER,
                        `sleepDurationMinutes` INTEGER,
                        `deepSleepPercent` REAL,
                        `remSleepPercent` REAL,
                        `hrvBaseline` INTEGER,
                        `restingHeartRate` INTEGER,
                        `restingHrRatio` REAL,
                        `stepCount` INTEGER,
                        `zLnHrv` REAL,
                        `zRhr` REAL,
                        `recoveryFlags` TEXT,
                        `hrvSigma` REAL,
                        `rollingMu` REAL,
                        `rhrDeltaBpm` REAL,
                        `lateNadir` INTEGER,
                        `stagesSuspicious` INTEGER,
                        `isCalibrating` INTEGER,
                        `hrvScoreContribution` REAL,
                        `rhrScoreContribution` REAL,
                        `durationScoreContribution` REAL,
                        `architectureScoreContribution` REAL,
                        `loadContribution` REAL,
                        `sRest` REAL,
                        `weightKg` REAL,
                        `bodyFatPercent` REAL,
                        `bloodPressureSystolic` INTEGER,
                        `bloodPressureDiastolic` INTEGER,
                        `avgSleepingSpo2` REAL,
                        `hrv_mu_mssd` REAL,
                        `hrv_sigma_mssd` REAL,
                        `rhr_bpm` REAL,
                        `rhr_sigma` REAL DEFAULT NULL,
                        `baseline_calculated_at_date` TEXT,
                        `hr_max` REAL,
                        `snapshot_profile` TEXT,
                        `snapshot_calibration_phase` TEXT,
                        `hrv_sigma_prior` REAL,
                        `ras_scaling_factor` REAL,
                        `baseline_observation_count` INTEGER,
                        `trimpWorkoutOnly` REAL,
                        `trimpEverydayHr` REAL,
                        `rasWorkoutOnly` REAL,
                        `rasEverydayHr` REAL,
                        `totalRasWorkoutOnly` REAL,
                        `totalRasEverydayHr` REAL,
                        `atlWorkoutOnly` REAL,
                        `atlEverydayHr` REAL,
                        `ctlWorkoutOnly` REAL,
                        `ctlEverydayHr` REAL,
                        `strainRatioWorkoutOnly` REAL,
                        `strainRatioEverydayHr` REAL,
                        `loadScoreWorkoutOnly` REAL,
                        `loadScoreEverydayHr` REAL,
                        `readinessWorkoutOnly` REAL,
                        `readinessEverydayHr` REAL,
                        `everydayCoverageMinutes` INTEGER,
                        `everydayLoadConfidence` TEXT,
                        `diag_zLnHrv` REAL,
                        `diag_zRhr` REAL,
                        `diag_lnSigma` REAL,
                        `diag_rollingMu` REAL,
                        `diag_rhrDeltaBpm` REAL,
                        `diag_isCalibrating` INTEGER NOT NULL,
                        `diag_stagesSuspicious` INTEGER NOT NULL,
                        `diag_lateNadir` INTEGER NOT NULL,
                        `diag_hrvMissing` INTEGER NOT NULL,
                        `diag_timezoneJump` INTEGER NOT NULL,
                        `diag_configHashCode` INTEGER,
                        `diag_phaseName` TEXT,
                        `contrib_hrvScore` REAL,
                        `contrib_rhrScore` REAL,
                        `contrib_durationScore` REAL,
                        `contrib_architectureScore` REAL,
                        `contrib_loadContribution` REAL,
                        PRIMARY KEY(`dateMidnightMs`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_summaries_dateMidnightMs` ON `daily_summaries` (`dateMidnightMs`)")
            }
        }

    private val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `insight_dismissals` (
                        `dateMidnightMs` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        PRIMARY KEY(`dateMidnightMs`, `type`)
                    )
                    """.trimIndent()
                )
            }
        }

    private val MIGRATION_3_4 =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audit_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `occurredAtEpochMs` INTEGER NOT NULL,
                        `detail` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_audit_events_occurredAtEpochMs`
                    ON `audit_events` (`occurredAtEpochMs`)
                    """.trimIndent(),
                )
            }
        }
        
    private val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN supplementalSleepDurationMinutes INTEGER")
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN napCount INTEGER")
            }
        }

    private val MIGRATION_5_6 =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_records ADD COLUMN intensityLevel TEXT")
            }
        }

    val all: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
        )
}
