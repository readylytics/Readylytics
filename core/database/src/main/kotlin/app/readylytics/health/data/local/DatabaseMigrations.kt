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
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_daily_summaries_dateMidnightMs` ON `daily_summaries` (`dateMidnightMs`)",
                )
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
                    """.trimIndent(),
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
                // SCORE-001: unified-TRIMP column, additive/nullable; lazily backfilled by the next
                // walk-forward recompute. Read paths COALESCE(modelTrimp, trimp) until then.
                db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimp REAL")

                // HC-005/OD-3: raw per-record steps table, purely so a later steps DeletionChange
                // can resolve the deleted record's own (startTime, endTime). Never read for scoring.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `step_records` (
                        `id` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `count` INTEGER NOT NULL,
                        `deviceName` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )

                // DB-002: redundant secondary index on daily_summaries' own primary key column.
                db.execSQL("DROP INDEX IF EXISTS `index_daily_summaries_dateMidnightMs`")
            }
        }

    private val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // DB-001: Schema preparation for integer primary key migration.

                // Rebuild heart_rate_records
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `heart_rate_records_new` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceRecordId` TEXT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `beatsPerMinute` INTEGER NOT NULL,
                        `recordType` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `deviceName` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `heart_rate_records_new` (`sourceRecordId`, `timestampMs`, `beatsPerMinute`, `recordType`, `sessionId`, `deviceName`)
                    SELECT `id`, `timestampMs`, `beatsPerMinute`, `recordType`, `sessionId`, `deviceName` FROM `heart_rate_records`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `heart_rate_records`")
                db.execSQL("ALTER TABLE `heart_rate_records_new` RENAME TO `heart_rate_records`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_heart_rate_records_sourceRecordId_timestampMs` ON `heart_rate_records` (`sourceRecordId`, `timestampMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_heart_rate_records_timestampMs` ON `heart_rate_records` (`timestampMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_heart_rate_records_sessionId_recordType_beatsPerMinute` ON `heart_rate_records` (`sessionId`, `recordType`, `beatsPerMinute`)",
                )

                // Rebuild hrv_records
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hrv_records_new` (
                        `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceRecordId` TEXT NOT NULL,
                        `timestampMs` INTEGER NOT NULL,
                        `rmssdMs` REAL NOT NULL,
                        `recordType` TEXT NOT NULL,
                        `sessionId` TEXT,
                        `deviceName` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `hrv_records_new` (`sourceRecordId`, `timestampMs`, `rmssdMs`, `recordType`, `sessionId`, `deviceName`)
                    SELECT `id`, `timestampMs`, `rmssdMs`, `recordType`, `sessionId`, `deviceName` FROM `hrv_records`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `hrv_records`")
                db.execSQL("ALTER TABLE `hrv_records_new` RENAME TO `hrv_records`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_hrv_records_sourceRecordId_timestampMs` ON `hrv_records` (`sourceRecordId`, `timestampMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hrv_records_timestampMs` ON `hrv_records` (`timestampMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hrv_records_recordType_timestampMs` ON `hrv_records` (`recordType`, `timestampMs`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hrv_records_sessionId` ON `hrv_records` (`sessionId`)")
            }
        }

    val all: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        )
}
