package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN acuteLoadRecovery REAL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN trainingLoadReadinessWorkoutOnly REAL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN trainingLoadReadinessEverydayHr REAL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN trainingReadinessWorkoutOnly REAL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN trainingReadinessEverydayHr REAL")
        }
    }
