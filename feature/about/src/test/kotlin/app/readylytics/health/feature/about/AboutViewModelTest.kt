package app.readylytics.health.feature.about

import app.readylytics.health.domain.preferences.AboutPreferences
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var aboutPreferences: AboutPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        aboutPreferences = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun dismissAbout_updatesPreferenceOnce_andCompletesCallback() =
        runTest(testDispatcher) {
            var callbackCount = 0
            val viewModel = AboutViewModel(aboutPreferences)

            viewModel.dismissAbout { callbackCount++ }
            advanceUntilIdle()

            coVerify(exactly = 1) { aboutPreferences.updateAboutDismissed(true) }
            assertEquals(1, callbackCount)
        }
}
