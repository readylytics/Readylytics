package app.readylytics.health.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v11 -> v12: Add missing index on step_records.startTime for keyset pagination
 * and efficient range queries / cleanup deletions.
 */
val MIGRATION_11_12 =
    object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_step_records_startTime` " +
                    "ON `step_records` (`startTime`)",
            )
        }
    }
