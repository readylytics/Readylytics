package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration21To22 : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createTables(db)
        migrateData(db)
    }

    private fun createTables(db: SupportSQLiteDatabase) {
        createCoverageTables(db)
        createStagingTables(db)
    }

    private fun createCoverageTables(db: SupportSQLiteDatabase) {
        // Create new tables
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `minute_coverage` (
                `bucketStartMs` INTEGER NOT NULL,
                `visibleGeneration` INTEGER NOT NULL,
                `tier` TEXT NOT NULL,
                `quality` TEXT NOT NULL,
                `sourceSelectionId` TEXT,
                PRIMARY KEY(`bucketStartMs`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `hr_source_minute_contributions` (
                `sourceRecordRef` INTEGER NOT NULL,
                `bucketStartMs` INTEGER NOT NULL,
                `generation` INTEGER NOT NULL,
                `firstSampleMs` INTEGER NOT NULL,
                `lastSampleMs` INTEGER NOT NULL,
                `deviceName` TEXT NOT NULL,
                `bpmHistogram` TEXT NOT NULL,
                PRIMARY KEY(`sourceRecordRef`, `bucketStartMs`, `generation`),
                FOREIGN KEY(`sourceRecordRef`) REFERENCES `health_source_records`(`id`) 
                ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_hr_source_minute_contributions_bucketStartMs_generation` " +
            "ON `hr_source_minute_contributions` (`bucketStartMs`, `generation`)",
        )
    }

    private fun createStagingTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `staged_hr_sources` (
                `runId` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `recordType` TEXT NOT NULL,
                `originPackage` TEXT,
                `startMs` INTEGER NOT NULL,
                `endExclusiveMs` INTEGER NOT NULL,
                `lastModifiedMs` INTEGER,
                `payloadComplete` INTEGER NOT NULL,
                PRIMARY KEY(`runId`, `sourceId`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `staged_hr_samples` (
                `runId` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `timestampMs` INTEGER NOT NULL,
                `beatsPerMinute` INTEGER NOT NULL,
                `recordType` TEXT NOT NULL,
                `sessionId` TEXT,
                `deviceName` TEXT,
                PRIMARY KEY(`runId`, `sourceId`, `timestampMs`)
            )
            """.trimIndent(),
        )
    }

    private fun migrateData(db: SupportSQLiteDatabase) {
        // Alter hr_minute_buckets
        db.execSQL("ALTER TABLE `hr_minute_buckets` ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0")

        // Migrate existing buckets to minute_coverage
        db.execSQL(
            """
            INSERT OR IGNORE INTO `minute_coverage` (`bucketStartMs`, `visibleGeneration`, `tier`, `quality`, `sourceSelectionId`)
            SELECT DISTINCT `bucketStartMs`, 0, 'LEGACY_WARM', 'LEGACY_UNKNOWN', NULL FROM `hr_minute_buckets`
            """.trimIndent(),
        )
    }
}
