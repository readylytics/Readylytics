package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * WP-18/S3: additive scan-staging tables. Both start empty — staging is in-flight operational
 * state, so there is nothing to backfill and no physiological data is rewritten. Old scan
 * checkpoints stay replayable: a resumed run with no staged rows simply rescans its chunk, which is
 * the WP-06 complete-chunk replay fallback.
 */
object Migration22To23 : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scan_seen_ids` (
                `runId` TEXT NOT NULL,
                `chunkId` TEXT NOT NULL,
                `recordType` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                PRIMARY KEY(`runId`, `chunkId`, `recordType`, `sourceId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scan_seen_ids_runId_chunkId_recordType` " +
                "ON `scan_seen_ids` (`runId`, `chunkId`, `recordType`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scan_type_state` (
                `runId` TEXT NOT NULL,
                `chunkId` TEXT NOT NULL,
                `recordType` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `stagedCount` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`runId`, `chunkId`, `recordType`)
            )
            """.trimIndent(),
        )
    }
}
