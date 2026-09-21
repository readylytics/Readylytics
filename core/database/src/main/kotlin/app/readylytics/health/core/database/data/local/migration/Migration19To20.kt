package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_19_20 =
    object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN originPackage TEXT")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN recordStartMs INTEGER")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN recordEndExclusiveMs INTEGER")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN lastModifiedMs INTEGER")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN metadataState TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE health_source_records ADD COLUMN sourceRevision INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_health_source_records_recordType_metadataState_recordStartMs` " +
                    "ON `health_source_records` (`recordType`, `metadataState`, `recordStartMs`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `dirty_ranges` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sourceGeneration` INTEGER NOT NULL, " +
                    "`startEpochDay` INTEGER NOT NULL, " +
                    "`endEpochDayInclusive` INTEGER NOT NULL, " +
                    "`nextEpochDay` INTEGER NOT NULL, " +
                    "`reason` TEXT NOT NULL, " +
                    "`scoringSnapshotId` TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_dirty_ranges_sourceGeneration_id` " +
                    "ON `dirty_ranges` (`sourceGeneration`, `id`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_mutation_state` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`sourceGeneration` INTEGER NOT NULL, " +
                    "`maintenanceOperationId` TEXT, " +
                    "`maintenancePhase` TEXT, " +
                    "`backfillAfterSourceRef` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL("INSERT INTO health_mutation_state VALUES (1, 0, NULL, NULL, 0)")
        }
    }
