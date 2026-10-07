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

    /**
     * Bumps the mutation-state generation, then durably journals `[start, endInclusive]` for
     * [reason]/[snapshotId] -- the increment-then-append order every other dirty-range journal
     * call site in this codebase uses (`SelectedSourcePrunerImpl.journalPrunedPage`,
     * `DeletionJournalContext.journalAffectedDates`), bundled into one interface call for callers
     * that only hold this interface and not the Room DAOs those call sites inject directly (e.g.
     * `DailySyncUseCase`, across the `core:healthconnect`/`core:database` module boundary). No-op
     * default (returns -1) for read-only/fake stores that never write a ticket.
     */
    suspend fun journalDirtyRange(
        start: LocalDate,
        endInclusive: LocalDate,
        reason: String,
        snapshotId: String,
    ): Long = -1
}

/** `dirty_ranges.reason` values written only by builds that still journaled data aging. */
val RETIRED_AGING_DIRTY_REASONS: List<String> = listOf("HOT_TIER_ROLLUP", "RETENTION_CLEANUP")
