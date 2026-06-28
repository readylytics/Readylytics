package app.readylytics.health.ui.settings

import app.readylytics.health.R
import app.readylytics.health.data.preferences.CircadianThresholdPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.core.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SleepAndThresholdSettingsViewModelTest {
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepo = mockk<ScoringRepository>(relaxed = true)
    private val circadianPrefs = mockk<CircadianThresholdPreferences>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sleepViewModel: SleepSettingsViewModel
    private lateinit var thresholdViewModel: ThresholdSettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { settingsRepo.userPreferences } returns MutableStateFlow(UserPreferences())
        coEvery { circadianPrefs.overrideMinutesFlow } returns MutableStateFlow(null)

        sleepViewModel =
            SleepSettingsViewModel(settingsRepo, scoringRepo, kotlinx.coroutines.CoroutineScope(testDispatcher))
        thresholdViewModel = ThresholdSettingsViewModel(settingsRepo, scoringRepo, circadianPrefs)
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

            coVerify { settingsRepo.updateHrvBaselineOverride(50f) }
            coVerify { scoringRepo.computeAndPersistDailySummary() }
        }

    @Test
    fun sleepSettingsViewModel_restingHrPercentileChanged_persisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.RestingHrPercentileChanged(8))
            advanceUntilIdle()

            coVerify { settingsRepo.updateRestingHrPercentile(8) }
            coVerify { scoringRepo.computeAndPersistDailySummary() }
        }

    @Test
    fun sleepSettingsViewModel_invalidHrvOverride_notPersisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.HrvBaselineChanged("invalid"))
            advanceUntilIdle()

            coVerify(exactly = 0) { settingsRepo.updateHrvBaselineOverride(any()) }
        }

    @Test
    fun thresholdSettingsViewModel_validWrite_persisted() =
        runTest {
            thresholdViewModel.onEvent(SettingsEvent.HrvOptimalThresholdChanged(1.1f))
            advanceUntilIdle()

            coVerify { settingsRepo.updateHrvOptimalThreshold(1.1f) }
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
