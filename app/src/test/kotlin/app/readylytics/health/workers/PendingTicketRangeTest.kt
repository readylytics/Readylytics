package app.readylytics.health.workers

import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * #295/#302: a recompute-only drain walks only the pending tickets' span, and the large-recalc
 * diagnostic must report that span instead of the full retained window.
 */
class PendingTicketRangeTest {
    @Test
    fun `no pending tickets resolves no drain range`() {
        assertNull(pendingTicketRange(emptyList()))
    }

    @Test
    fun `drain range spans earliest cursor through latest end`() {
        val tickets =
            listOf(
                ticket(1, next = SEP_29, end = SEP_30),
                ticket(2, next = SEP_29.minusDays(1), end = SEP_29),
                ticket(3, next = SEP_30, end = SEP_30),
            )

        assertEquals(ScoreInvalidation.AffectedRange(SEP_29.minusDays(1), SEP_30), pendingTicketRange(tickets))
    }

    @Test
    fun `identical duplicate tickets collapse to their own two-day range`() {
        val tickets = (1L..100L).map { ticket(it, next = SEP_29, end = SEP_30) }

        assertEquals(ScoreInvalidation.AffectedRange(SEP_29, SEP_30), pendingTicketRange(tickets))
    }

    private fun ticket(
        id: Long,
        next: LocalDate,
        end: LocalDate,
    ) = DirtyTicket(
        id = id,
        sourceGeneration = id,
        nextDay = next,
        endInclusive = end,
        scoringSnapshotId = "ACTIVE",
        reason = "AUTHORITATIVE_SOURCE_REPLACEMENT",
    )

    private companion object {
        val SEP_29: LocalDate = LocalDate.of(2026, 9, 29)
        val SEP_30: LocalDate = LocalDate.of(2026, 9, 30)
    }
}
