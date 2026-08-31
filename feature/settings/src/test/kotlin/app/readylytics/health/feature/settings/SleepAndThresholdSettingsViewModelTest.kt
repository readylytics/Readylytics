package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.CircadianThresholdPreferences
import app.readylytics.health.core.model.domain.preferences.SleepSettings
import app.readylytics.health.core.model.domain.preferences.ThresholdSettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.sync.HistoricalResyncController
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.feature.settings.R
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepAndThresholdSettingsViewModelTest {
    private val settingsReader = mockk<UserPreferencesReader>()
    private val sleepSettings = mockk<SleepSettings>(relaxed = true)
    private val thresholdSettings = mockk<ThresholdSettings>(relaxed = true)
    private val scoringRepo = mockk<ScoringRepository>(relaxed = true)
    private val circadianPrefs = mockk<CircadianThresholdPreferences>(relaxed = true)
    private val resyncController = mockk<HistoricalResyncController>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testClock: java.time.Clock =
        java.time.Clock.fixed(java.time.Instant.parse("2026-08-27T00:00:00Z"), java.time.ZoneOffset.UTC)

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
                resyncController,
                kotlinx.coroutines.CoroutineScope(testDispatcher),
                testClock,
            )
        thresholdViewModel =
            ThresholdSettingsViewModel(
                settingsReader,
                thresholdSettings,
                scoringRepo,
                circadianPrefs,
                testClock,
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
            coVerify { scoringRepo.computeAndPersistDailySummary(any()) }
        }

    @Test
    fun sleepSettingsViewModel_restingHrPercentileChanged_persisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.RestingHrPercentileChanged(8))
            advanceUntilIdle()

            coVerify { sleepSettings.updateRestingHrPercentile(8) }
            coVerify { scoringRepo.computeAndPersistDailySummary(any()) }
        }

    @Test
    fun sleepSettingsViewModel_invalidHrvOverride_notPersisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.HrvBaselineChanged("invalid"))
            advanceUntilIdle()

            coVerify(exactly = 0) { sleepSettings.updateHrvBaselineOverride(any()) }
        }

    @Test
    fun sleepSettingsViewModel_mapsBiphasicSleepPolicyState() =
        runTest {
            every { settingsReader.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        coreMergeGapMinutes = 210,
                        supplementalCutoffMinutesOfDay = 1260,
                        minimumCountedSleepSegmentMinutes = 20,
                        supplementalArchitectureCoveragePercent = 80,
                    ),
                )

            sleepViewModel =
                SleepSettingsViewModel(
                    settingsReader,
                    sleepSettings,
                    scoringRepo,
                    resyncController,
                    kotlinx.coroutines.CoroutineScope(testDispatcher),
                    testClock,
                )

            val state =
                sleepViewModel.uiState.first {
                    it.coreMergeGapMinutes == 210 &&
                        it.supplementalCutoffMinutesOfDay == 1260 &&
                        it.minimumCountedSleepSegmentMinutes == 20 &&
                        it.supplementalArchitectureCoveragePercent == 80
                }
            assertEquals(210, state.coreMergeGapMinutes)
            assertEquals(1260, state.supplementalCutoffMinutesOfDay)
            assertEquals(20, state.minimumCountedSleepSegmentMinutes)
            assertEquals(80, state.supplementalArchitectureCoveragePercent)
        }

    @Test
    fun sleepSettingsViewModel_coreMergeGapChanged_persisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.CoreMergeGapMinutesChanged(210))
            advanceUntilIdle()

            coVerify { sleepSettings.updateCoreMergeGapMinutes(210) }
            coVerify { scoringRepo.computeAndPersistDailySummary(any()) }
        }

    @Test
    fun sleepSettingsViewModel_invalidSupplementalCutoff_notPersisted() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.SupplementalCutoffMinutesOfDayChanged(845))
            advanceUntilIdle()

            coVerify(exactly = 0) { sleepSettings.updateSupplementalCutoffMinutesOfDay(any()) }
        }

    @Test
    fun thresholdSettingsViewModel_validWrite_persisted() =
        runTest {
            thresholdViewModel.onEvent(SettingsEvent.HrvOptimalThresholdChanged(1.1f))
            advanceUntilIdle()

            coVerify { thresholdSettings.updateHrvOptimalThreshold(1.1f) }
        }

    @Test
    fun bodyTempElevatedThreshold_validValue_persisted() =
        runTest {
            thresholdViewModel.onEvent(SettingsEvent.BodyTempElevatedThresholdChanged(0.75f))
            advanceUntilIdle()

            coVerify { thresholdSettings.updateBodyTempElevatedThreshold(0.75f) }
        }

    @Test
    fun bodyTempElevatedThreshold_outOfRange_notPersisted() =
        runTest {
            thresholdViewModel.onEvent(SettingsEvent.BodyTempElevatedThresholdChanged(2.0f))
            advanceUntilIdle()

            coVerify(exactly = 0) { thresholdSettings.updateBodyTempElevatedThreshold(any()) }
        }

    @Test
    fun thresholdSettingsViewModel_circadianOverride_persisted() =
        runTest {
            thresholdViewModel.onEvent(SettingsEvent.CircadianThresholdOverrideChanged(30))
            advanceUntilIdle()

            coVerify { circadianPrefs.setOverride(30) }
            coVerify { scoringRepo.computeAndPersistDailySummary(any()) }
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

    @Test
    fun `weight profile change is persisted without triggering a recompute`() =
        runTest {
            sleepViewModel.onEvent(
                SettingsEvent.SleepScoreWeightProfileChanged(SleepScoreWeightProfile.DURATION_FOCUSED),
            )
            advanceUntilIdle()

            coVerify { sleepSettings.updateSleepScoreWeightProfile(SleepScoreWeightProfile.DURATION_FOCUSED) }
            coVerify { scoringRepo.computeAndPersistDailySummary(any()) }
            coVerify(exactly = 0) { resyncController.requestScoreRecompute() }
        }

    @Test
    fun `off-step oversleep onset is rejected`() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.HypersomniaOnsetPercentChanged(103))
            advanceUntilIdle()

            coVerify(exactly = 0) { sleepSettings.updateHypersomniaOnsetPercent(any()) }
        }

    @Test
    fun `valid oversleep onset is persisted`() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.HypersomniaOnsetPercentChanged(115))
            advanceUntilIdle()

            coVerify { sleepSettings.updateHypersomniaOnsetPercent(115) }
            coVerify { scoringRepo.computeAndPersistDailySummary(any()) }
        }

    @Test
    fun `recalculate action enqueues a recompute-only pass`() =
        runTest {
            sleepViewModel.onEvent(SettingsEvent.RecalculateScores)
            advanceUntilIdle()

            coVerify { resyncController.requestScoreRecompute() }
        }

    @Test
    fun `sleepSettingsViewModel maps sleepScoreWeightProfile and hypersomniaOnsetPercent`() =
        runTest {
            every { settingsReader.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        sleepScoreWeightProfile = SleepScoreWeightProfile.RECOVERY_FOCUSED,
                        hypersomniaOnsetPercent = 110,
                    ),
                )

            val vm =
                SleepSettingsViewModel(
                    settingsReader,
                    sleepSettings,
                    scoringRepo,
                    resyncController,
                    kotlinx.coroutines.CoroutineScope(testDispatcher),
                    testClock,
                )

            val state =
                vm.uiState.first {
                    it.sleepScoreWeightProfile == SleepScoreWeightProfile.RECOVERY_FOCUSED &&
                        it.hypersomniaOnsetPercent == 110
                }
            assertEquals(SleepScoreWeightProfile.RECOVERY_FOCUSED, state.sleepScoreWeightProfile)
            assertEquals(110, state.hypersomniaOnsetPercent)
            assertTrue(state.hasPendingSleepScoreRecalc)
        }

    @Test
    fun `recalc pending is false at baseline`() =
        runTest {
            val vm =
                SleepSettingsViewModel(
                    settingsReader,
                    sleepSettings,
                    scoringRepo,
                    resyncController,
                    kotlinx.coroutines.CoroutineScope(testDispatcher),
                    testClock,
                )

            val job =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    vm.uiState.collect { }
                }
            advanceUntilIdle()

            assertFalse(vm.uiState.value.hasPendingSleepScoreRecalc)
            job.cancel()
        }

    @Test
    fun `recalc pending true when goal differs from the last-recalc baseline`() =
        runTest {
            every { settingsReader.userPreferences } returns
                MutableStateFlow(UserPreferences(goalSleepHours = 9f))

            val vm =
                SleepSettingsViewModel(
                    settingsReader,
                    sleepSettings,
                    scoringRepo,
                    resyncController,
                    kotlinx.coroutines.CoroutineScope(testDispatcher),
                    testClock,
                )

            val job =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    vm.uiState.collect { }
                }
            advanceUntilIdle()

            assertTrue(vm.uiState.value.hasPendingSleepScoreRecalc)
            job.cancel()
        }

    @Test
    fun `recalc pending true when profile differs from the last-recalc baseline`() =
        runTest {
            every { settingsReader.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        sleepScoreWeightProfile = SleepScoreWeightProfile.DURATION_FOCUSED,
                        lastRecalcSleepScoreWeightProfile = SleepScoreWeightProfile.BALANCED,
                    ),
                )

            val vm =
                SleepSettingsViewModel(
                    settingsReader,
                    sleepSettings,
                    scoringRepo,
                    resyncController,
                    kotlinx.coroutines.CoroutineScope(testDispatcher),
                    testClock,
                )

            val job =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    vm.uiState.collect { }
                }
            advanceUntilIdle()

            assertTrue(vm.uiState.value.hasPendingSleepScoreRecalc)
            job.cancel()
        }

    @Test
    fun `recalc pending true when hypersomnia differs from the last-recalc baseline`() =
        runTest {
            every { settingsReader.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        hypersomniaOnsetPercent = 110,
                        lastRecalcHypersomniaOnsetPercent = SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT,
                    ),
                )

            val vm =
                SleepSettingsViewModel(
                    settingsReader,
                    sleepSettings,
                    scoringRepo,
                    resyncController,
                    kotlinx.coroutines.CoroutineScope(testDispatcher),
                    testClock,
                )

            val job =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    vm.uiState.collect { }
                }
            advanceUntilIdle()

            assertTrue(vm.uiState.value.hasPendingSleepScoreRecalc)
            job.cancel()
        }

    @Test
    fun `recalc pending false once the worker-recorded baseline matches live inputs`() =
        runTest {
            every { settingsReader.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        goalSleepHours = 9f,
                        lastRecalcGoalSleepHours = 9f,
                        lastRecalcSleepScoreWeightProfile = SleepScoreWeightProfile.BALANCED,
                        lastRecalcHypersomniaOnsetPercent = SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT,
                    ),
                )

            val vm =
                SleepSettingsViewModel(
                    settingsReader,
                    sleepSettings,
                    scoringRepo,
                    resyncController,
                    kotlinx.coroutines.CoroutineScope(testDispatcher),
                    testClock,
                )

            val job =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    vm.uiState.collect { }
                }
            advanceUntilIdle()

            assertFalse(vm.uiState.value.hasPendingSleepScoreRecalc)
            job.cancel()
        }
}
