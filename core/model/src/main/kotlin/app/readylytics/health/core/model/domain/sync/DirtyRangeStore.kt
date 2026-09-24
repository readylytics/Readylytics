package app.readylytics.health.core.model.domain.sync

import java.time.LocalDate

data class DirtyTicket(
    val id: Long,
    val sourceGeneration: Long,
    val nextDay: LocalDate,
    val endInclusive: LocalDate,
    val scoringSnapshotId: String,
    /** Why the work was journaled (the `dirty_ranges.reason` column); diagnostics only. */
    val reason: String = "",
) {
    init {
        require(!nextDay.isAfter(endInclusive.plusDays(1))) {
            "nextDay ($nextDay) must not be after endInclusive + 1 (${endInclusive.plusDays(1)})"
        }
    }
}

interface DirtyRangeStore {
    /** Drop work outside the retained scoring window, preserving every retained day. */
    suspend fun discardBefore(retentionStart: LocalDate) = Unit

    /**
     * Drop pending work journaled by the retired hot-tier rollup / retention-cleanup paths. Aging
     * data out no longer invalidates retained summaries, but tickets written by older builds would
     * otherwise keep re-enqueueing a multi-week recompute on every start. Returns rows deleted.
     */
    suspend fun discardRetiredAgingTickets(): Int = 0

    suspend fun pending(limit: Int = 100): List<DirtyTicket>
}

/** `dirty_ranges.reason` values written only by builds that still journaled data aging. */
val RETIRED_AGING_DIRTY_REASONS: List<String> = listOf("HOT_TIER_ROLLUP", "RETENTION_CLEANUP")
