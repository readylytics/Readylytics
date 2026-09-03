package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_15_16 =
    object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 1. Recreate hr_minute_buckets with deviceName in primary key
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `hr_minute_buckets_new` (
                    `bucketStartMs` INTEGER NOT NULL,
                    `bucketEndMs` INTEGER NOT NULL,
                    `minBpm` INTEGER NOT NULL,
                    `maxBpm` INTEGER NOT NULL,
                    `avgBpm` REAL NOT NULL,
                    `sampleCount` INTEGER NOT NULL,
                    `recordType` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `deviceName` TEXT NOT NULL DEFAULT '',
                    `p5Bpm` INTEGER,
                    `p25Bpm` INTEGER,
                    `p50Bpm` INTEGER,
                    `p75Bpm` INTEGER,
                    `p95Bpm` INTEGER,
                    PRIMARY KEY(`bucketStartMs`, `recordType`, `sessionId`, `deviceName`)
                )
                """.trimIndent(),
            )

            db.execSQL(
                """
                INSERT INTO `hr_minute_buckets_new` (
                    `bucketStartMs`, `bucketEndMs`, `minBpm`, `maxBpm`, `avgBpm`,
                    `sampleCount`, `recordType`, `sessionId`, `deviceName`,
                    `p5Bpm`, `p25Bpm`, `p50Bpm`, `p75Bpm`, `p95Bpm`
                )
                SELECT
                    `bucketStartMs`, `bucketEndMs`, `minBpm`, `maxBpm`, `avgBpm`,
                    `sampleCount`, `recordType`, `sessionId`, COALESCE(`deviceName`, ''),
                    `p5Bpm`, `p25Bpm`, `p50Bpm`, `p75Bpm`, `p95Bpm`
                FROM `hr_minute_buckets`
                """.trimIndent(),
            )

            db.execSQL("DROP TABLE `hr_minute_buckets`")
            db.execSQL("ALTER TABLE `hr_minute_buckets_new` RENAME TO `hr_minute_buckets`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_hr_minute_buckets_sessionId_recordType` " +
                    "ON `hr_minute_buckets` (`sessionId`, `recordType`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_hr_minute_buckets_bucketStartMs_bucketEndMs` " +
                    "ON `hr_minute_buckets` (`bucketStartMs`, `bucketEndMs`)",
            )

            // 2. R2-ARCH-003: Normalize empty string deviceName to NULL across vitals tables
            listOf(
                "weight_records",
                "body_fat_records",
                "blood_pressure_records",
                "oxygen_saturation_records",
                "body_temperature_records",
            ).forEach { table ->
                db.execSQL("UPDATE `$table` SET `deviceName` = NULL WHERE `deviceName` = ''")
            }
        }
    }
