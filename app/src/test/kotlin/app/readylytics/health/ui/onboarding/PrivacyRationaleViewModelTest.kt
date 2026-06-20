package app.readylytics.health.ui.onboarding

import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyRationaleViewModelTest {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: PrivacyRationaleViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun appTheme_exposesCorrectThemeFromPreferences() = runTest(testDispatcher) {
        val userPreferences = UserPreferences(appTheme = AppTheme.DARK)
        every { settingsRepository.userPreferences } returns flowOf(userPreferences)

        viewModel = PrivacyRationaleViewModel(settingsRepository)

        val collectJob = launch { viewModel.appTheme.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(AppTheme.DARK, viewModel.appTheme.value)
        collectJob.cancel()
    }

    @Test
    fun appTheme_defaultThemeIsSystem() = runTest(testDispatcher) {
        every { settingsRepository.userPreferences } returns flowOf(UserPreferences(appTheme = AppTheme.SYSTEM))

        viewModel = PrivacyRationaleViewModel(settingsRepository)

        val collectJob = launch { viewModel.appTheme.collect {} }
        testScheduler.advanceUntilIdle()

        assertEquals(AppTheme.SYSTEM, viewModel.appTheme.value)
        collectJob.cancel()
    }
}
