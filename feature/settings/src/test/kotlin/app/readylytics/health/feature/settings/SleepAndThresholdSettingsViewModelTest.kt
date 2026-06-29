package app.readylytics.health.feature.settings

import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.preferences.CircadianThresholdPreferences
import app.readylytics.health.domain.preferences.SleepSettings
import app.readylytics.health.domain.preferences.ThresholdSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.feature.settings.R
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepAndThresholdSettingsViewModelTest {
    private val settingsReader = mockk<UserPreferencesReader>()
    private val sleepSettings = mockk<SleepSettings>(relaxed = true)
    private val thresholdSettings = mockk<ThresholdSettings>(relaxed = true)
    private val scoringRepo = mockk<ScoringRepository>(relaxed = true)
    private val circadianPrefs = mockk<CircadianThresholdPreferences>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sleepViewModel: SleepSettingsViewModel
    private lateinit var thresholdViewModel: ThresholdSettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsReader.userPreferences } returns MutableStateFlow(UserPreferences())
        every { circadianPrefs.overrideMinutesFlow } returns MutableStateFlow(null)

        sleepViewModel =
            SleepSettingsViewModel(
                settingsReader,
                sleepSettings,
                scoringRepo,
                kotlinx.coroutines.CoroutineScope(testDispatcher),
            )
        thresholdViewModel =
            ThresholdSettingsViewModel(
                settingsReader,
                thresholdSettings,
                scoringRepo,
                circadianPrefs,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sleepSettingsViewModel_validHrvOverride_persisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.HrvBaselineChanged("50"))
            advanceUntilIdle()

            coVerify { sleepSettings.updateHrvBaselineOverride(50f) }
            coVerify { scoringRepo.computeAndPersistDailySummary() }
        }

    @Test
    fun sleepSettingsViewModel_restingHrPercentileChanged_persisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.RestingHrPercentileChanged(8))
            advanceUntilIdle()

            coVerify { sleepSettings.updateRestingHrPercentile(8) }
            coVerify { scoringRepo.computeAndPersistDailySummary() }
        }

    @Test
    fun sleepSettingsViewModel_invalidHrvOverride_notPersisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.HrvBaselineChanged("invalid"))
            advanceUntilIdle()

            coVerify(exactly = 0) { sleepSettings.updateHrvBaselineOverride(any()) }
        }

    @Test
    fun thresholdSettingsViewModel_validWrite_persisted() =
        runTest {
            thresholdViewModel.onEvent(SettingsEvent.HrvOptimalThresholdChanged(1.1f))
            advanceUntilIdle()

            coVerify { thresholdSettings.updateHrvOptimalThreshold(1.1f) }
        }

    @Test
    fun thresholdSettingsViewModel_circadianOverride_persisted() =
        runTest {
            thresholdViewModel.onEvent(SettingsEvent.CircadianThresholdOverrideChanged(30))
            advanceUntilIdle()

            coVerify { circadianPrefs.setOverride(30) }
            coVerify { scoringRepo.computeAndPersistDailySummary() }
        }

    @Test
    fun thresholdSettingsViewModel_invalidCircadianOverride_showsError() =
        runTest {
            val job =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    thresholdViewModel.consolidatedState.collect { }
                }
            thresholdViewModel.onEvent(SettingsEvent.CircadianThresholdOverrideChanged(120))
            advanceUntilIdle()

            assertEquals(
                UiText.StringRes(R.string.error_threshold_invalid_range),
                thresholdViewModel.consolidatedState.value.thresholdError,
            )
            job.cancel()
        }
}
