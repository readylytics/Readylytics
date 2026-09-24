package app.readylytics.health.ui.crashreport

import app.readylytics.health.core.model.domain.crashreport.CrashReportStore
import app.readylytics.health.core.model.domain.crashreport.RecalcDiagnosticStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashReportViewModelTest {
    @Test
    fun showPromptReflectsHasReportAtConstructionTime() {
        val withReport = CrashReportViewModel(FakeCrashReportStore(hasReport = true))
        val withoutReport = CrashReportViewModel(FakeCrashReportStore(hasReport = false))

        assertTrue(withReport.showPrompt.value)
        assertTrue(withReport.hasReport.value)
        assertFalse(withoutReport.showPrompt.value)
        assertFalse(withoutReport.hasReport.value)
    }

    @Test
    fun dismissHidesPromptButKeepsReport() {
        val store = FakeCrashReportStore(hasReport = true)
        val viewModel = CrashReportViewModel(store)

        viewModel.dismiss()

        assertFalse(viewModel.showPrompt.value)
        assertTrue(viewModel.hasReport.value)
        assertEquals(0, store.deleteCallCount)
    }

    @Test
    fun consumeReportHidesPromptAndDeletesReport() {
        val store = FakeCrashReportStore(hasReport = true)
        val viewModel = CrashReportViewModel(store)

        viewModel.consumeReport()

        assertFalse(viewModel.showPrompt.value)
        assertFalse(viewModel.hasReport.value)
        assertEquals(1, store.deleteCallCount)
    }

    @Test
    fun clearReportHidesPromptAndDeletesReport() {
        val store = FakeCrashReportStore(hasReport = true)
        val viewModel = CrashReportViewModel(store)

        viewModel.clearReport()

        assertFalse(viewModel.showPrompt.value)
        assertFalse(viewModel.hasReport.value)
        assertEquals(1, store.deleteCallCount)
    }

    @Test
    fun crashReportTakesPriorityOverRecalcDiagnostic() {
        val viewModel =
            CrashReportViewModel(FakeCrashReportStore(hasReport = true), FakeRecalcStore(hasReport = true))

        assertEquals(StartupReportKind.CRASH, viewModel.promptKind.value)
        assertTrue(viewModel.showPrompt.value)
    }

    @Test
    fun recalcDiagnosticAloneShowsPromptWithoutFlaggingACrashReport() {
        val recalc = FakeRecalcStore(hasReport = true)
        val viewModel = CrashReportViewModel(FakeCrashReportStore(hasReport = false), recalc)

        assertEquals(StartupReportKind.RECALC_DIAGNOSTIC, viewModel.promptKind.value)
        assertTrue(viewModel.showPrompt.value)
        assertFalse(viewModel.hasReport.value)
        assertEquals("recalc log", viewModel.promptReportText())
    }

    @Test
    fun consumePromptReportDeletesOnlyTheShownReport() {
        val crash = FakeCrashReportStore(hasReport = false)
        val recalc = FakeRecalcStore(hasReport = true)
        val viewModel = CrashReportViewModel(crash, recalc)

        viewModel.consumePromptReport()

        assertFalse(viewModel.showPrompt.value)
        assertEquals(1, recalc.deleteCallCount)
        assertEquals(0, crash.deleteCallCount)
    }

    @Test
    fun noReportsMeansNoPrompt() {
        val viewModel =
            CrashReportViewModel(FakeCrashReportStore(hasReport = false), FakeRecalcStore(hasReport = false))

        assertNull(viewModel.promptKind.value)
        assertFalse(viewModel.showPrompt.value)
    }

    private class FakeRecalcStore(
        private var hasReport: Boolean,
    ) : RecalcDiagnosticStore {
        var deleteCallCount = 0
            private set

        override fun hasReport(): Boolean = hasReport

        override fun append(entry: String) {
            hasReport = true
        }

        override fun read(): String? = if (hasReport) "recalc log" else null

        override fun delete() {
            deleteCallCount++
            hasReport = false
        }

        override fun reportFile(): File = File("recalc")
    }

    private class FakeCrashReportStore(
        private var hasReport: Boolean,
    ) : CrashReportStore {
        var deleteCallCount = 0
            private set

        override fun hasReport(): Boolean = hasReport

        override fun write(report: String) {
            hasReport = true
        }

        override fun read(): String? = null

        override fun delete() {
            deleteCallCount++
            hasReport = false
        }

        override fun reportFile(): File = File("dummy")
    }
}
