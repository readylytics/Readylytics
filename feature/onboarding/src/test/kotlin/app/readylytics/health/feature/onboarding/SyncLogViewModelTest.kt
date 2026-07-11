package app.readylytics.health.feature.onboarding

import app.readylytics.health.domain.logcat.LogcatCaptureStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncLogViewModelTest {
    private lateinit var logcatCaptureStore: LogcatCaptureStore
    private lateinit var viewModel: SyncLogViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        logcatCaptureStore = mockk(relaxed = true)
        viewModel = SyncLogViewModel(logcatCaptureStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startPolling_callsCaptureAndUpdatesState() =
        runTest {
            coEvery { logcatCaptureStore.capture(any()) } returns "Mock log entry"
            viewModel.startPolling()
            coVerify(atLeast = 1) { logcatCaptureStore.capture(durationMinutes = 3) }
            assert(viewModel.logText.value == "Mock log entry")
            viewModel.stopPolling()
        }
}
