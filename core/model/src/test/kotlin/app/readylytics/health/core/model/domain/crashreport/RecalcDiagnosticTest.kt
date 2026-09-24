package app.readylytics.health.core.model.domain.crashreport

import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecalcDiagnosticTest {
    private val today = LocalDate.of(2026, 9, 24)

    @Test
    fun `records only unexpected triggers above the normal sync window`() {
        val threeDays = today.minusDays(2)
        val fourDays = today.minusDays(3)

        assertFalse(shouldRecordRecalcDiagnostic(RecalcTrigger.STARTUP_PENDING_DIRTY, threeDays, today))
        assertTrue(shouldRecordRecalcDiagnostic(RecalcTrigger.STARTUP_PENDING_DIRTY, fourDays, today))
        assertTrue(shouldRecordRecalcDiagnostic(RecalcTrigger.PERIODIC_SYNC_ESCALATION, fourDays, today))
        assertFalse(shouldRecordRecalcDiagnostic(RecalcTrigger.SETTINGS_CHANGE, today.minusDays(365), today))
        assertFalse(shouldRecordRecalcDiagnostic(RecalcTrigger.USER_RESYNC, today.minusDays(365), today))
        assertFalse(shouldRecordRecalcDiagnostic(RecalcTrigger.STARTUP_SCORING_VERSION, today.minusDays(365), today))
    }

    @Test
    fun `format names the trigger, range and pending tickets`() {
        val report =
            formatRecalcDiagnostic(
                RecalcDiagnostic(
                    timestampIso = "2026-09-24T03:00:00Z",
                    appVersionName = "1.2.3",
                    appVersionCode = 45,
                    trigger = RecalcTrigger.STARTUP_PENDING_DIRTY,
                    triggerDetail = "pendingDirty=[x]",
                    recomputeOnly = true,
                    startDate = today.minusDays(59),
                    endDate = today,
                    pendingTickets =
                        listOf(
                            DirtyTicket(
                                id = 7,
                                sourceGeneration = 3,
                                nextDay = today.minusDays(59),
                                endInclusive = today,
                                scoringSnapshotId = "ACTIVE",
                                reason = "AUTHORITATIVE_SOURCE_REPLACEMENT",
                            ),
                        ),
                ),
            )

        assertContains(report, "Trigger: STARTUP_PENDING_DIRTY")
        assertContains(report, "Detail: pendingDirty=[x]")
        assertContains(report, "Mode: recompute-only")
        assertContains(report, "Range: 2026-07-27..2026-09-24 (60 days)")
        assertContains(report, "#7 AUTHORITATIVE_SOURCE_REPLACEMENT next=2026-07-27 end=2026-09-24 gen=3")
        assertContains(report, "1.2.3 (45)")
    }

    @Test
    fun `append keeps only the newest entries`() {
        var log: String? = null
        repeat(5) { log = appendRecalcDiagnosticEntry(log, "entry-$it", maxEntries = 3) }

        val entries = log!!.split("\n----\n")
        assertEquals(listOf("entry-2", "entry-3", "entry-4"), entries)
    }

    @Test
    fun `unknown stored trigger names decode to a non-reported trigger`() {
        assertEquals(RecalcTrigger.SETTINGS_CHANGE, RecalcTrigger.fromName(null))
        assertEquals(RecalcTrigger.SETTINGS_CHANGE, RecalcTrigger.fromName("NOT_A_TRIGGER"))
        assertEquals(RecalcTrigger.CATCH_UP_CAP, RecalcTrigger.fromName("CATCH_UP_CAP"))
    }
}
