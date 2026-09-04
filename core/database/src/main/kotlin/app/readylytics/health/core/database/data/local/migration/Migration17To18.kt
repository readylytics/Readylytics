package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vo2_max_records` (
                    `id` TEXT NOT NULL PRIMARY KEY,
                    `timestampMs` INTEGER NOT NULL,
                    `vo2Max` REAL NOT NULL,
                    `measurementMethod` INTEGER,
                    `deviceName` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_vo2_max_records_timestampMs` " +
                    "ON `vo2_max_records` (`timestampMs`)",
            )
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2Max REAL DEFAULT NULL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2MaxSource TEXT DEFAULT NULL")
        }
    }
