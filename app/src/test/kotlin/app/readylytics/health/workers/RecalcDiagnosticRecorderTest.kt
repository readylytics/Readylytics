package app.readylytics.health.workers

import app.readylytics.health.core.model.domain.crashreport.RecalcDiagnosticStore
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RecalcDiagnosticRecorderTest {
    private val today = LocalDate.of(2026, 9, 24)
    private val store = RecordingStore()
    private val recorder =
        RecalcDiagnosticRecorder(
            store = store,
            dirtyRangeStore =
                object : DirtyRangeStore {
                    override suspend fun pending(limit: Int): List<DirtyTicket> = emptyList()
                },
            clock = Clock.fixed(Instant.parse("2026-09-24T03:00:00Z"), ZoneOffset.UTC),
            ioDispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun `large range from an unexpected trigger is appended`() =
        runTest {
            recorder.recordIfLarge(RecalcTrigger.STARTUP_PENDING_DIRTY, "pendingDirty=[x]", recomputeOnly = true) {
                ScoreInvalidation.AffectedRange(today.minusDays(59), today)
            }

            assertEquals(1, store.entries.size)
            assertTrue(store.entries.single().contains("Trigger: STARTUP_PENDING_DIRTY"))
            assertTrue(store.entries.single().contains("(60 days)"))
        }

    @Test
    fun `normal-sized range is not recorded`() =
        runTest {
            recorder.recordIfLarge(RecalcTrigger.STARTUP_PENDING_DIRTY, null, recomputeOnly = true) {
                ScoreInvalidation.AffectedRange(today.minusDays(2), today)
            }

            assertTrue(store.entries.isEmpty())
        }

    @Test
    fun `expected triggers never resolve the range or record`() =
        runTest {
            var resolved = false
            recorder.recordIfLarge(RecalcTrigger.SETTINGS_CHANGE, null, recomputeOnly = true) {
                resolved = true
                ScoreInvalidation.AffectedRange(today.minusDays(365), today)
            }

            assertFalse(resolved)
            assertTrue(store.entries.isEmpty())
        }

    @Test
    fun `a failing range lookup never propagates`() =
        runTest {
            recorder.recordIfLarge(RecalcTrigger.PERIODIC_SYNC_ESCALATION, null, recomputeOnly = false) {
                error("settings unavailable")
            }

            assertTrue(store.entries.isEmpty())
        }

    private class RecordingStore : RecalcDiagnosticStore {
        val entries = mutableListOf<String>()

        override fun hasReport(): Boolean = entries.isNotEmpty()

        override fun append(entry: String) {
            entries += entry
        }

        override fun read(): String? = entries.joinToString("\n").ifEmpty { null }

        override fun delete() = entries.clear()

        override fun reportFile(): File = File("recalc")
    }
}
