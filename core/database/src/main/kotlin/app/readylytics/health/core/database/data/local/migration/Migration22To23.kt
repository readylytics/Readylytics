package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * WP-18/S3: additive scan-staging tables. Both start empty — staging is in-flight operational
 * state, so there is nothing to backfill and no physiological data is rewritten. Old scan
 * checkpoints stay replayable: a resumed run with no staged rows simply rescans its chunk, which is
 * the WP-06 complete-chunk replay fallback.
 *
 * Also carries two Phase 2 Task 10 indexes, added only after `EXPLAIN QUERY PLAN` on the real
 * production queries proved each one necessary (see `Phase2QueryPlanTest`): `scan_seen_ids.sourceId`
 * (the Task 9 GC's runId-less existence check) and `heart_rate_records(timestampMs,
 * sourceRecordRef)` (the Task 8 rollup streamer's keyset page). Still additive to this unreleased
 * v23 -- no new migration version.
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
        // Phase 2 Task 10: proven by EXPLAIN QUERY PLAN -- see the KDoc on ScanSeenIdEntity's
        // `sourceId` index. Without it, SourceRecordDao.pageUnreferencedSourceIds' runId-less
        // `NOT EXISTS (... WHERE sourceId = ?)` GC check fell back to a bare table scan.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_scan_seen_ids_sourceId` ON `scan_seen_ids` (`sourceId`)",
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
        // Phase 2 Task 10: proven by EXPLAIN QUERY PLAN -- see the KDoc on
        // HeartRateRecordEntity's `index_hr_v10_timestamp_source` index. Without it,
        // HeartRateDao.pagePlausibleSamplesForRollup's keyset ORDER BY needed
        // `USE TEMP B-TREE FOR RIGHT PART OF ORDER BY` to break timestampMs ties.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_hr_v10_timestamp_source` " +
                "ON `heart_rate_records` (`timestampMs`, `sourceRecordRef`)",
        )
    }
}
