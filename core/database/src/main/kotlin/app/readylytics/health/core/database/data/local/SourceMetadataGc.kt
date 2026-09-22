package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * PERF-003: bounded collection of source metadata that nothing references any more. Retention
 * deletes raw rows but previously left one metadata row per historical parent behind, so a
 * million-parent history kept accumulating identities that every backup then had to page through.
 *
 * Deliberately conservative: a row is collectable only when it is BOTH of an eligible record type
 * (`HEART_RATE`/`HRV` -- the types whose `health_source_records` row is a derived index over raw
 * children stored elsewhere) AND unreferenced (no raw children, no warm contribution evidence, no
 * in-flight staged/scan reference). The type allowlist is what keeps `DISTANCE`/`ELEVATION_GAINED`
 * interval sources -- which are primary data with no children anywhere, written by
 * `RoomHealthChangeIngestionStore.persistIntervalEnrichment` -- out of scope entirely. Deleting a
 * still-referenced or non-indexed row would break a backup FK, destroy OD-1 warm lineage, or erase
 * an interval record outright, so "unsure" always means "keep". Must only be invoked
 * after the caller's own raw-row deletion batches for the run have already committed -- a row
 * whose children are deleted later in the same run has to be judged against the post-deletion
 * state, never the pre-deletion one.
 */
internal object SourceMetadataGc {
    const val PAGE_SIZE = 500
    const val LIMIT_PER_RUN = 10_000

    suspend fun collect(
        dao: SourceRecordDao,
        pageSize: Int = PAGE_SIZE,
        limitPerRun: Int = LIMIT_PER_RUN,
    ): Int {
        var deleted = 0
        var afterRef = 0L
        while (deleted < limitPerRun) {
            currentCoroutineContext().ensureActive()
            val remaining = (limitPerRun - deleted).coerceAtMost(pageSize)
            val page = dao.pageUnreferencedSourceIds(afterRef, remaining)
            if (page.isEmpty()) break
            deleted += dao.deleteSourcesByRefs(page)
            afterRef = page.last()
            yield()
        }
        return deleted
    }
}
