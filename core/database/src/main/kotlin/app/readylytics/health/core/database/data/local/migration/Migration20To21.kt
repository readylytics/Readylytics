package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration20To21 : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add OD-3 Canonical workout metadata columns
        db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpSourceRevision INTEGER")
        db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpSnapshotId TEXT")
        db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpAlgorithmRevision INTEGER")
        db.execSQL("ALTER TABLE workout_records ADD COLUMN modelTrimpQuality TEXT")
    }
}
