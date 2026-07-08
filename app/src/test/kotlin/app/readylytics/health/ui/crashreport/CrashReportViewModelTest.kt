package app.readylytics.health.ui.crashreport

import app.readylytics.health.domain.crashreport.CrashReportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
