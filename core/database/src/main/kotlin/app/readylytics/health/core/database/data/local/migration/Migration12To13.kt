package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v12 -> v13: Add nullable residualFatigue column to daily_summaries for the
 * shadow-mode Residual Fatigue score (Banister model). Existing rows are NULL
 * until the scoring pipeline populates them.
 */
val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE daily_summaries ADD COLUMN residualFatigue REAL DEFAULT NULL",
            )
        }
    }
