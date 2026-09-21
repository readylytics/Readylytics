package app.readylytics.health.core.model.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class DirtyRangeContractTest {
    @Test
    fun dirtyTicketValidatesBounds() {
        val today = LocalDate.of(2026, 9, 11)
        val ticket =
            DirtyTicket(
                id = 1L,
                sourceGeneration = 2L,
                nextDay = today,
                endInclusive = today.plusDays(5),
                scoringSnapshotId = "SNAP_1",
            )
        assertEquals(1L, ticket.id)
        assertEquals(2L, ticket.sourceGeneration)
        assertEquals(today, ticket.nextDay)
        assertEquals(today.plusDays(5), ticket.endInclusive)
        assertEquals("SNAP_1", ticket.scoringSnapshotId)
    }

    @Test
    fun dirtyTicketAllowsNextDayImmediatelyAfterEndInclusive() {
        val today = LocalDate.of(2026, 9, 11)
        // Representing a completed ticket that advanced past endInclusive before deletion
        val ticket =
            DirtyTicket(
                id = 1L,
                sourceGeneration = 1L,
                nextDay = today.plusDays(1),
                endInclusive = today,
                scoringSnapshotId = "SNAP_1",
            )
        assertEquals(today.plusDays(1), ticket.nextDay)
    }

    @Test
    fun dirtyTicketRejectsNextDayFartherThanEndInclusivePlusOne() {
        val today = LocalDate.of(2026, 9, 11)
        assertThrows(IllegalArgumentException::class.java) {
            DirtyTicket(
                id = 1L,
                sourceGeneration = 1L,
                nextDay = today.plusDays(2),
                endInclusive = today,
                scoringSnapshotId = "SNAP_1",
            )
        }
    }

    @Test
    fun dirtyRangeStoreContract() =
        kotlinx.coroutines.test.runTest {
            val fakeStore =
                object : DirtyRangeStore {
                    private val tickets = mutableListOf<DirtyTicket>()

                    fun add(ticket: DirtyTicket) = tickets.add(ticket)

                    override suspend fun pending(limit: Int): List<DirtyTicket> =
                        tickets.take(limit)
                }

            assertEquals(0, fakeStore.pending().size)

            val d = LocalDate.of(2026, 9, 1)
            fakeStore.add(DirtyTicket(1L, 1L, d, d.plusDays(10), "S1"))
            fakeStore.add(DirtyTicket(2L, 2L, d.plusDays(1), d.plusDays(5), "S2"))

            val pending = fakeStore.pending(1)
            assertEquals(1, pending.size)
            assertEquals(1L, pending.first().id)

            val all = fakeStore.pending(10)
            assertEquals(2, all.size)
            assertEquals(2L, all[1].id)
        }
}
