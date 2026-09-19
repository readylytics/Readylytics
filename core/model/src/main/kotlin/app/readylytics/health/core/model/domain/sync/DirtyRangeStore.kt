package app.readylytics.health.core.model.domain.sync

import java.time.LocalDate

data class DirtyTicket(
    val id: Long,
    val sourceGeneration: Long,
    val nextDay: LocalDate,
    val endInclusive: LocalDate,
    val scoringSnapshotId: String,
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

    suspend fun pending(limit: Int = 100): List<DirtyTicket>
}
