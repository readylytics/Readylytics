package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import java.time.LocalDate
import java.time.ZoneId

interface SelectedSourcePruner {
    suspend fun prune(
        start: LocalDate,
        endInclusive: LocalDate,
        selections: Map<HealthDataType, String?>,
        zoneId: ZoneId,
    )

    /**
     * WP-17 (HC-102): retroactive, bounded-page sibling of [prune] for the one-time startup
     * repair of workouts already persisted before this app version added de-selection pruning.
     * [prune] only ever runs going forward from a new sync/resync window, so a workout recorded
     * by a device the user later deselected -- and already imported before that -- is otherwise
     * stranded in Room forever.
     *
     * Deletes every `workout_records` row (and its route points) in `[start, endInclusive]` whose
     * device does not match [selectedDevice], paging through matches in bounded batches so a
     * multi-year range never loads its full result set into memory. Returns the
     * [ScoreInvalidation.AffectedRange] spanning the dates of every deleted workout (in [zoneId]),
     * or null if nothing was deleted.
     *
     * Fix-round-3: the implementation also durably journals a `dirty_ranges` ticket per deleted
     * page, atomically with that page's own delete (see `SelectedSourcePrunerImpl`'s
     * `journalPrunedPage`) -- this return value is a convenience for callers/tests, not the sole
     * record of "what needs recompute". A worker killed after a page commits but before it calls
     * the caller's recompute must still recompute that page's dates on retry even though a retry's
     * prune finds nothing left to delete and reports null; the durable ticket is what makes that
     * possible.
     */
    suspend fun pruneExcludedWorkouts(
        start: LocalDate,
        endInclusive: LocalDate,
        selectedDevice: String,
        zoneId: ZoneId,
    ): ScoreInvalidation.AffectedRange?
}
