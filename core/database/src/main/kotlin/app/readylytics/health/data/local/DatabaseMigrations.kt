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
                        `diag_isCalibrating` INTEGER, 
                        `diag_stagesSuspicious` INTEGER, 
                        `diag_lateNadir` INTEGER, 
                        `diag_hrvMissing` INTEGER, 
                        `diag_timezoneJump` INTEGER, 
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

    val all: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
        )
}
