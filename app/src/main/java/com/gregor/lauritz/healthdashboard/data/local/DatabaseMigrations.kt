package com.gregor.lauritz.healthdashboard.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

object DatabaseMigrations {
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN rhrRatio REAL")
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvZScore REAL")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN rhrRatio REAL")
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvZScore REAL")
            }
        }

    val MIGRATION_2_3 =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvBaseline REAL")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvBaseline REAL")
            }
        }

    val MIGRATION_3_4 =
        object : Migration(3, 4) {
            private val sql =
                listOf(
                    """
                    CREATE TABLE daily_summaries_new (
                        dateMidnightMs INTEGER NOT NULL PRIMARY KEY,
                        sleepScore REAL,
                        loadScore REAL,
                        readinessScore REAL,
                        strainRatio REAL,
                        nocturnalRhr REAL,
                        nocturnalHrv REAL,
                        sleepDurationMinutes INTEGER,
                        deepSleepPercent REAL,
                        remSleepPercent REAL,
                        totalTrimp REAL,
                        rhrRatio REAL,
                        hrvBaseline REAL
                    )
                    """.trimIndent(),
                    """
                    INSERT INTO daily_summaries_new
                        (dateMidnightMs, sleepScore, loadScore, readinessScore, strainRatio,
                         nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent,
                         remSleepPercent, totalTrimp, rhrRatio, hrvBaseline)
                    SELECT dateMidnightMs, sleepScore, loadScore, NULL, strainRatio,
                           nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent,
                           remSleepPercent, totalTrimp, rhrRatio, hrvBaseline
                    FROM daily_summaries
                    """.trimIndent(),
                    "DROP TABLE daily_summaries",
                    "ALTER TABLE daily_summaries_new RENAME TO daily_summaries",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_4_5 =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHeartRate REAL")
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrRatio REAL")
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrBaseline REAL")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHeartRate REAL")
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrRatio REAL")
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN restingHrBaseline REAL")
            }
        }

    val MIGRATION_5_6 =
        object : Migration(5, 6) {
            private val sql =
                listOf(
                    "DROP TABLE IF EXISTS daily_summaries_new",
                    "CREATE TABLE daily_summaries_new (" +
                        "dateMidnightMs INTEGER NOT NULL PRIMARY KEY, " +
                        "sleepScore REAL, loadScore REAL, readinessScore REAL, " +
                        "strainRatio REAL, nocturnalRhr INTEGER, nocturnalHrv INTEGER, " +
                        "sleepDurationMinutes INTEGER, deepSleepPercent REAL, " +
                        "remSleepPercent REAL, totalTrimp REAL, rhrRatio REAL, " +
                        "hrvBaseline INTEGER, restingHeartRate INTEGER, " +
                        "restingHrRatio REAL, restingHrBaseline INTEGER)",
                    "INSERT INTO daily_summaries_new (dateMidnightMs, sleepScore, loadScore, readinessScore, strainRatio, nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent, remSleepPercent, totalTrimp, rhrRatio, hrvBaseline, restingHeartRate, restingHrRatio, restingHrBaseline) SELECT dateMidnightMs, sleepScore, loadScore, readinessScore, strainRatio, nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent, remSleepPercent, totalTrimp, rhrRatio, hrvBaseline, restingHeartRate, restingHrRatio, restingHrBaseline FROM daily_summaries",
                    "DROP TABLE daily_summaries",
                    "ALTER TABLE daily_summaries_new RENAME TO daily_summaries",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_6_7 =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_records ADD COLUMN avgHr INTEGER NOT NULL DEFAULT 0")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE workout_records ADD COLUMN avgHr INTEGER NOT NULL DEFAULT 0")
            }
        }

    val MIGRATION_7_8 =
        object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_heart_rate_records_timestampMs`
                    ON `heart_rate_records` (`timestampMs`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_heart_rate_records_recordType`
                    ON `heart_rate_records` (`recordType`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_heart_rate_records_sessionId`
                    ON `heart_rate_records` (`sessionId`)
                    """.trimIndent(),
                )
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_heart_rate_records_timestampMs`
                    ON `heart_rate_records` (`timestampMs`)
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_heart_rate_records_recordType`
                    ON `heart_rate_records` (`recordType`)
                    """.trimIndent(),
                )
                connection.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_heart_rate_records_sessionId`
                    ON `heart_rate_records` (`sessionId`)
                    """.trimIndent(),
                )
            }
        }

    val MIGRATION_8_9 =
        object : Migration(8, 9) {
            private val sql =
                listOf(
                    "CREATE INDEX IF NOT EXISTS index_workout_records_startTime ON workout_records (startTime)",
                    "CREATE INDEX IF NOT EXISTS index_hrv_records_timestampMs ON hrv_records (timestampMs)",
                    "CREATE INDEX IF NOT EXISTS index_hrv_records_recordType_timestampMs ON hrv_records (recordType, timestampMs)",
                    "CREATE INDEX IF NOT EXISTS index_hrv_records_sessionId ON hrv_records (sessionId)",
                    "CREATE INDEX IF NOT EXISTS index_sleep_sessions_startTime ON sleep_sessions (startTime)",
                    "CREATE INDEX IF NOT EXISTS index_sleep_sessions_endTime ON sleep_sessions (endTime)",
                    "DROP INDEX IF EXISTS index_heart_rate_records_recordType",
                    "DROP INDEX IF EXISTS index_heart_rate_records_sessionId",
                    "CREATE INDEX IF NOT EXISTS index_heart_rate_records_recordType_timestampMs ON heart_rate_records (recordType, timestampMs)",
                    "CREATE INDEX IF NOT EXISTS index_heart_rate_records_sessionId_recordType_beatsPerMinute ON heart_rate_records (sessionId, recordType, beatsPerMinute)",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_9_10 =
        object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN paiScore REAL")
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN totalPai REAL")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN paiScore REAL")
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN totalPai REAL")
            }
        }

    val MIGRATION_10_11 =
        object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN stepCount INTEGER")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN stepCount INTEGER")
            }
        }

    val MIGRATION_11_12 =
        object : Migration(11, 12) {
            private val indexSql =
                "CREATE INDEX IF NOT EXISTS index_daily_summaries_dateMidnightMs ON daily_summaries (dateMidnightMs)"

            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN zLnHrv REAL")
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN zRhr REAL")
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN recoveryFlags TEXT")
                db.execSQL(indexSql)
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN zLnHrv REAL")
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN zRhr REAL")
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN recoveryFlags TEXT")
                connection.execSQL(indexSql)
            }
        }

    val MIGRATION_12_13 =
        object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sleep_sessions ADD COLUMN startZoneOffsetSeconds INTEGER")
                db.execSQL("ALTER TABLE sleep_sessions ADD COLUMN endZoneOffsetSeconds INTEGER")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE sleep_sessions ADD COLUMN startZoneOffsetSeconds INTEGER")
                connection.execSQL("ALTER TABLE sleep_sessions ADD COLUMN endZoneOffsetSeconds INTEGER")
            }
        }

    val MIGRATION_13_14 =
        object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvSigma REAL")
            }

            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN hrvSigma REAL")
            }
        }

    val MIGRATION_14_15 =
        object : Migration(14, 15) {
            private val sql =
                listOf(
                    "ALTER TABLE daily_summaries ADD COLUMN rollingMu REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN rhrDeltaBpm REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN lateNadir INTEGER",
                    "ALTER TABLE daily_summaries ADD COLUMN stagesSuspicious INTEGER",
                    "ALTER TABLE daily_summaries ADD COLUMN isCalibrating INTEGER",
                    "ALTER TABLE daily_summaries ADD COLUMN hrvScoreContribution REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN rhrScoreContribution REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN durationScoreContribution REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN architectureScoreContribution REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN loadContribution REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN sRest REAL",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_15_16 =
        object : Migration(15, 16) {
            private val sql =
                listOf(
                    """
                    CREATE TABLE daily_summaries_new (
                        dateMidnightMs INTEGER NOT NULL PRIMARY KEY,
                        sleepScore REAL,
                        loadScore REAL,
                        readinessScore REAL,
                        strainRatio REAL,
                        nocturnalRhr INTEGER,
                        nocturnalHrv INTEGER,
                        sleepDurationMinutes INTEGER,
                        deepSleepPercent REAL,
                        remSleepPercent REAL,
                        totalTrimp REAL,
                        rhrRatio REAL,
                        hrvBaseline INTEGER,
                        restingHeartRate INTEGER,
                        restingHrRatio REAL,
                        restingHrBaseline INTEGER,
                        paiScore REAL,
                        totalPai REAL,
                        stepCount INTEGER,
                        zLnHrv REAL,
                        zRhr REAL,
                        recoveryFlags TEXT,
                        hrvSigma REAL,
                        diag_zLnHrv REAL,
                        diag_zRhr REAL,
                        diag_lnSigma REAL,
                        diag_rollingMu REAL,
                        diag_rhrDeltaBpm REAL,
                        diag_isCalibrating INTEGER NOT NULL,
                        diag_stagesSuspicious INTEGER NOT NULL,
                        diag_lateNadir INTEGER NOT NULL,
                        diag_hrvMissing INTEGER NOT NULL,
                        diag_timezoneJump INTEGER NOT NULL,
                        diag_configHashCode INTEGER,
                        diag_phaseName TEXT,
                        contrib_hrvScore REAL,
                        contrib_rhrScore REAL,
                        contrib_durationScore REAL,
                        contrib_architectureScore REAL,
                        contrib_loadContribution REAL,
                        rollingMu REAL,
                        rhrDeltaBpm REAL,
                        lateNadir INTEGER,
                        stagesSuspicious INTEGER,
                        isCalibrating INTEGER,
                        hrvScoreContribution REAL,
                        rhrScoreContribution REAL,
                        durationScoreContribution REAL,
                        architectureScoreContribution REAL,
                        loadContribution REAL,
                        sRest REAL
                    )
                    """.trimIndent(),
                    """
                    INSERT INTO daily_summaries_new
                        (dateMidnightMs, sleepScore, loadScore, readinessScore, strainRatio,
                         nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent,
                         remSleepPercent, totalTrimp, rhrRatio, hrvBaseline, restingHeartRate,
                         restingHrRatio, restingHrBaseline, paiScore, totalPai, stepCount,
                         zLnHrv, zRhr, recoveryFlags, hrvSigma,
                         diag_zLnHrv, diag_zRhr, diag_lnSigma, diag_rollingMu, diag_rhrDeltaBpm,
                         diag_isCalibrating, diag_stagesSuspicious, diag_lateNadir, diag_hrvMissing, diag_timezoneJump,
                         diag_configHashCode, diag_phaseName,
                         contrib_hrvScore, contrib_rhrScore, contrib_durationScore, contrib_architectureScore, contrib_loadContribution,
                         rollingMu, rhrDeltaBpm, lateNadir, stagesSuspicious, isCalibrating,
                         hrvScoreContribution, rhrScoreContribution, durationScoreContribution,
                         architectureScoreContribution, loadContribution, sRest)
                    SELECT dateMidnightMs, sleepScore, loadScore, readinessScore, strainRatio,
                           nocturnalRhr, nocturnalHrv, sleepDurationMinutes, deepSleepPercent,
                           remSleepPercent, totalTrimp, rhrRatio, hrvBaseline, restingHeartRate,
                           restingHrRatio, restingHrBaseline, paiScore, totalPai, stepCount,
                           zLnHrv, zRhr, recoveryFlags, hrvSigma,
                           zLnHrv, zRhr, hrvSigma, rollingMu, rhrDeltaBpm,

                           COALESCE(isCalibrating, 0), COALESCE(stagesSuspicious, 0), COALESCE(lateNadir, 0), 0, 0,
                           NULL, NULL,
                           hrvScoreContribution, rhrScoreContribution, durationScoreContribution, architectureScoreContribution, loadContribution,
                           rollingMu, rhrDeltaBpm, lateNadir, stagesSuspicious, isCalibrating,
                           hrvScoreContribution, rhrScoreContribution, durationScoreContribution,
                           architectureScoreContribution, loadContribution, sRest
                    FROM daily_summaries
                    """.trimIndent(),
                    "DROP TABLE daily_summaries",
                    "ALTER TABLE daily_summaries_new RENAME TO daily_summaries",
                    "CREATE INDEX IF NOT EXISTS index_daily_summaries_dateMidnightMs ON daily_summaries (dateMidnightMs)",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_16_17 =
        object : Migration(16, 17) {
            private val sql =
                listOf(
                    """
                    CREATE TABLE workout_records_new (
                        `id` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `exerciseType` TEXT NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        `zone1Minutes` REAL NOT NULL,
                        `zone2Minutes` REAL NOT NULL,
                        `zone3Minutes` REAL NOT NULL,
                        `zone4Minutes` REAL NOT NULL,
                        `zone5Minutes` REAL NOT NULL,
                        `trimp` REAL NOT NULL,
                        `avgHr` REAL NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                    """
                    INSERT INTO workout_records_new
                        (id, startTime, endTime, exerciseType, durationMinutes,
                         zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes,
                         zone5Minutes, trimp, avgHr)
                    SELECT id, startTime, endTime, exerciseType, durationMinutes,
                           zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes,
                           zone5Minutes, trimp, CAST(avgHr AS REAL)
                    FROM workout_records
                    """.trimIndent(),
                    "DROP TABLE workout_records",
                    "ALTER TABLE workout_records_new RENAME TO workout_records",
                    "CREATE INDEX IF NOT EXISTS `index_workout_records_startTime` ON `workout_records` (`startTime`)",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_17_18 =
        object : Migration(17, 18) {
            private val sql =
                listOf(
                    "ALTER TABLE sleep_sessions ADD COLUMN deviceName TEXT",
                    "ALTER TABLE heart_rate_records ADD COLUMN deviceName TEXT",
                    "ALTER TABLE hrv_records ADD COLUMN deviceName TEXT",
                    "ALTER TABLE workout_records ADD COLUMN deviceName TEXT",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_18_19 =
        object : Migration(18, 19) {
            private val sql =
                listOf(
                    """
                    CREATE TABLE sleep_stages (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `sessionId` TEXT NOT NULL,
                        `stageType` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `sleep_sessions`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                    "CREATE INDEX IF NOT EXISTS `index_sleep_stages_sessionId` ON `sleep_stages` (`sessionId`)",
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_stages_sessionId_startTime` ON `sleep_stages` (`sessionId`, `startTime`)",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_19_20 =
        object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // These columns were supposed to be added in 15->16 but were missed in the manual SQL.
                // However, fresh installs at 16+ already have them. We add them here for those who migrated.
                // We use try-catch because they might already exist for fresh installers.
                try {
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN diag_configHashCode INTEGER")
                } catch (_: Exception) {
                    // Ignore if column already exists
                }
                try {
                    db.execSQL("ALTER TABLE daily_summaries ADD COLUMN diag_phaseName TEXT")
                } catch (_: Exception) {
                    // Ignore if column already exists
                }
                try {
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_stages_sessionId_startTime` ON `sleep_stages` (`sessionId`, `startTime`)",
                    )
                } catch (_: Exception) {
                    // Ignore if index already exists or data violates uniqueness
                }
            }

            override fun migrate(connection: SQLiteConnection) {
                try {
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN diag_configHashCode INTEGER")
                } catch (_: Exception) {
                    // Ignore if column already exists
                }
                try {
                    connection.execSQL("ALTER TABLE daily_summaries ADD COLUMN diag_phaseName TEXT")
                } catch (_: Exception) {
                    // Ignore if column already exists
                }
                try {
                    connection.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_stages_sessionId_startTime` ON `sleep_stages` (`sessionId`, `startTime`)",
                    )
                } catch (_: Exception) {
                    // Ignore if index already exists or data violates uniqueness
                }
            }
        }

    val MIGRATION_20_21 =
        object : Migration(20, 21) {
            private val sql =
                listOf(
                    """
                    CREATE TABLE weight_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestampMs INTEGER NOT NULL,
                        weightKg REAL NOT NULL,
                        deviceName TEXT
                    )
                    """.trimIndent(),
                    "CREATE INDEX `index_weight_records_timestampMs` ON `weight_records` (`timestampMs`)",
                    "CREATE INDEX `index_weight_records_timestampMs_deviceName` ON `weight_records` (`timestampMs`, `deviceName`)",
                    """
                    CREATE TABLE body_fat_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestampMs INTEGER NOT NULL,
                        bodyFatPercent REAL NOT NULL,
                        deviceName TEXT
                    )
                    """.trimIndent(),
                    "CREATE INDEX `index_body_fat_records_timestampMs` ON `body_fat_records` (`timestampMs`)",
                    "CREATE INDEX `index_body_fat_records_timestampMs_deviceName` ON `body_fat_records` (`timestampMs`, `deviceName`)",
                    """
                    CREATE TABLE blood_pressure_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestampMs INTEGER NOT NULL,
                        systolicMmHg INTEGER NOT NULL,
                        diastolicMmHg INTEGER NOT NULL,
                        deviceName TEXT
                    )
                    """.trimIndent(),
                    "CREATE INDEX `index_blood_pressure_records_timestampMs` ON `blood_pressure_records` (`timestampMs`)",
                    "CREATE INDEX `index_blood_pressure_records_timestampMs_deviceName` ON `blood_pressure_records` (`timestampMs`, `deviceName`)",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_21_22 =
        object : Migration(21, 22) {
            private val sql =
                listOf(
                    "ALTER TABLE daily_summaries ADD COLUMN weightKg REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN bodyFatPercent REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN bloodPressureSystolic INTEGER",
                    "ALTER TABLE daily_summaries ADD COLUMN bloodPressureDiastolic INTEGER",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_22_23 =
        object : Migration(22, 23) {
            private val sql =
                listOf(
                    "ALTER TABLE daily_summaries ADD COLUMN hrv_mu_mssd REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN hrv_sigma_mssd REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN rhr_bpm REAL",
                    "ALTER TABLE daily_summaries ADD COLUMN baseline_calculated_at_date TEXT",
                    "ALTER TABLE daily_summaries ADD COLUMN baseline_version INTEGER DEFAULT 1",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val MIGRATION_23_24 =
        object : Migration(23, 24) {
            private val sql =
                listOf(
                    """
                    CREATE TABLE oxygen_saturation_records (
                        id TEXT NOT NULL PRIMARY KEY,
                        timestampMs INTEGER NOT NULL,
                        percentage REAL NOT NULL,
                        deviceName TEXT
                    )
                    """.trimIndent(),
                    "CREATE INDEX `index_oxygen_saturation_records_timestampMs` ON `oxygen_saturation_records` (`timestampMs`)",
                    "CREATE INDEX `index_oxygen_saturation_records_timestampMs_deviceName` ON `oxygen_saturation_records` (`timestampMs`, `deviceName`)",
                    "ALTER TABLE daily_summaries ADD COLUMN avgSleepingSpo2 REAL",
                )

            override fun migrate(db: SupportSQLiteDatabase) {
                sql.forEach { db.execSQL(it) }
            }

            override fun migrate(connection: SQLiteConnection) {
                sql.forEach { connection.execSQL(it) }
            }
        }

    val all =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
        )
}
