package app.readylytics.health.feature.settings

import app.readylytics.health.domain.crashreport.CrashReportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrashReportSettingsViewModelTest {
    @Test
    fun markSentDeletesReportAndClearsHasCrashReportFlag() {
        val store = FakeCrashReportStore(hasReport = true)
        val viewModel = CrashReportSettingsViewModel(store)

        assertTrue(viewModel.hasCrashReport.value)

        viewModel.markSent()

        assertEquals(1, store.deleteCallCount)
        assertFalse(viewModel.hasCrashReport.value)
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
