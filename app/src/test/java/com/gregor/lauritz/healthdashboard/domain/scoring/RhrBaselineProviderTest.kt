package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.PhysiologyConstants
import com.gregor.lauritz.healthdashboard.domain.scoring.BaselineComputer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class RhrBaselineProviderTest {
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val provider = AdaptiveRhrBaselineProvider(dailySummaryDao, settingsRepository, baselineComputer)
    
    @Before
    fun setUp() {
        coEvery { dailySummaryDao.getPreciseRhrBaseline(any()) } returns null
        coEvery { dailySummaryDao.getRoundedRhrBaseline(any()) } returns null
    }

    @Test
    fun getRhrBaseline_with_override_returns_override() =
        runTest {
            val overrideValue = 55f
            val prefs = UserPreferences(rhrBaselineOverride = overrideValue)
            coEvery { settingsRepository.userPreferences } returns flowOf(prefs)
            val baseline = provider.getRhrBaseline(Instant.now())
            assertEquals(overrideValue, baseline)
        }

    @Test
    fun getRhrBaseline_with_sufficient_data_returns_calculated() =
        runTest {
            val calculatedRhr = 62f
            val rhrValues = listOf(60, 62, 64, 61, 63, 62, 61)
            val dayMidnight = Instant.now()
            val prefs = UserPreferences(rhrBaselineOverride = null)
            coEvery { settingsRepository.userPreferences } returns flowOf(prefs)
            coEvery { baselineComputer.rhrHistory(any(), any()) } returns rhrValues
            coEvery { baselineComputer.resolveBaselineRhrBpm(any(), any()) } returns calculatedRhr
            val baseline = provider.getRhrBaseline(dayMidnight)
            assertEquals(calculatedRhr, baseline)
        }

    @Test
    fun getRhrBaseline_with_insufficient_data_returns_default() =
        runTest {
            val emptyRhrValues = emptyList<Int>()
            val dayMidnight = Instant.now()
            val prefs = UserPreferences(rhrBaselineOverride = null)
            coEvery { settingsRepository.userPreferences } returns flowOf(prefs)
            coEvery { baselineComputer.rhrHistory(any(), any()) } returns emptyRhrValues
            val baseline = provider.getRhrBaseline(dayMidnight)
            assertEquals(PhysiologyConstants.DEFAULT_RHR_BPM.toFloat(), baseline)
        }

    @Test
    fun getRhrBaseline_with_null_rhr_baseline_returns_default() =
        runTest {
            val dayMidnight = Instant.now()
            val prefs = UserPreferences(rhrBaselineOverride = null)
            coEvery { settingsRepository.userPreferences } returns flowOf(prefs)
            coEvery { baselineComputer.rhrHistory(any(), any()) } returns emptyList()
            val baseline = provider.getRhrBaseline(dayMidnight)
            assertEquals(PhysiologyConstants.DEFAULT_RHR_BPM.toFloat(), baseline)
        }
}
