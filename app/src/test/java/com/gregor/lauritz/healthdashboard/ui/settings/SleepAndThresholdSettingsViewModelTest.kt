package com.gregor.lauritz.healthdashboard.ui.settings

import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SleepAndThresholdSettingsViewModelTest {
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepo = mockk<ScoringRepository>(relaxed = true)
    private val circadianPrefs = mockk<CircadianThresholdPreferences>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var sleepViewModel: SleepSettingsViewModel
    private lateinit var thresholdViewModel: ThresholdSettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { settingsRepo.userPreferences } returns MutableStateFlow(mockk(relaxed = true))
        coEvery { circadianPrefs.overrideMinutesFlow } returns MutableStateFlow(null)
        
        sleepViewModel = SleepSettingsViewModel(settingsRepo, scoringRepo)
        thresholdViewModel = ThresholdSettingsViewModel(settingsRepo, scoringRepo, circadianPrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sleepSettingsViewModel_validHrvOverride_persisted() {
        sleepViewModel.onEvent(SettingsEvent.HrvBaselineChanged("50"))
        
        coVerify { settingsRepo.updateHrvBaselineOverride(50f) }
        coVerify { scoringRepo.computeAndPersistDailySummary() }
    }

    @Test
    fun sleepSettingsViewModel_invalidHrvOverride_notPersisted() {
        sleepViewModel.onEvent(SettingsEvent.HrvBaselineChanged("invalid"))
        
        coVerify(exactly = 0) { settingsRepo.updateHrvBaselineOverride(any()) }
    }

    @Test
    fun thresholdSettingsViewModel_validWrite_persisted() {
        thresholdViewModel.onEvent(SettingsEvent.HrvOptimalThresholdChanged(60f))
        
        coVerify { settingsRepo.updateHrvOptimalThreshold(60f) }
    }

    @Test
    fun thresholdSettingsViewModel_circadianOverride_persisted() {
        thresholdViewModel.onEvent(SettingsEvent.CircadianThresholdOverrideChanged(30))
        
        coVerify { circadianPrefs.setOverride(30) }
        coVerify { scoringRepo.computeAndPersistDailySummary() }
    }

    @Test
    fun thresholdSettingsViewModel_invalidCircadianOverride_showsError() {
        // CircadianThresholdValue.tryCreate fails for > 90
        thresholdViewModel.onEvent(SettingsEvent.CircadianThresholdOverrideChanged(120))
        
        assertEquals("Invalid threshold value. Range: 0-90 minutes.", thresholdViewModel.consolidatedState.value.thresholdError)
    }
}
