package app.readylytics.health.ui.logcat

import app.readylytics.health.domain.logcat.LogcatCaptureStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LogcatCaptureViewModelTest {
    @Test
    fun captureReturnsStoreResult() =
        runTest {
            val viewModel = LogcatCaptureViewModel(FakeLogcatCaptureStore(captureResult = "log lines"))

            assertEquals("log lines", viewModel.capture(30))
        }

    @Test
    fun captureReturnsNullWhenStoreCaptureFails() =
        runTest {
            val viewModel = LogcatCaptureViewModel(FakeLogcatCaptureStore(captureResult = null))

            assertNull(viewModel.capture(30))
        }

    @Test
    fun captureFileDelegatesToStore() {
        val file = File("dummy")
        val viewModel = LogcatCaptureViewModel(FakeLogcatCaptureStore(captureResult = null, file = file))

        assertEquals(file, viewModel.captureFile())
    }

    private class FakeLogcatCaptureStore(
        private val captureResult: String?,
        private val file: File = File("dummy"),
    ) : LogcatCaptureStore {
        override suspend fun capture(durationMinutes: Int): String? = captureResult

        override fun captureFile(): File = file
    }
}
