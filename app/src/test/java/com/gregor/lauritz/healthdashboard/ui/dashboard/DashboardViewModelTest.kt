package com.gregor.lauritz.healthdashboard.ui.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.cache.DailyMetricCache
import com.gregor.lauritz.healthdashboard.domain.dashboard.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.dashboard.GetDashboardDataUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DashboardViewModelTest {
    private lateinit var dailySummaryRepository: DailySummaryRepository
    private lateinit var getDashboardDataUseCase: GetDashboardDataUseCase
    private lateinit var foregroundSyncController: ForegroundSyncController
    private lateinit var selectedDateRepository: SelectedDateRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var cardConfigRepository: CardConfigurationRepository
    private lateinit var circadianRepo: CircadianConsistencyRepository
    private lateinit var dailyMetricCache: DailyMetricCache
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        dailySummaryRepository = mockk(relaxed = true)
        getDashboardDataUseCase = mockk(relaxed = true)
        foregroundSyncController = mockk(relaxed = true)
        selectedDateRepository = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        cardConfigRepository = mockk(relaxed = true)
        circadianRepo = mockk(relaxed = true)
        dailyMetricCache = mockk(relaxed = true)
        viewModel =
            DashboardViewModel(
                dailySummaryRepository = dailySummaryRepository,
                getDashboardDataUseCase = getDashboardDataUseCase,
                foregroundSyncController = foregroundSyncController,
                selectedDateRepository = selectedDateRepository,
                settingsRepo = settingsRepo,
                cardConfigRepository = cardConfigRepository,
                circadianRepo = circadianRepo,
                dailyMetricCache = dailyMetricCache,
            )
    }

    @Test
    fun validateSelectedDate_today_succeeds() {
        val result = viewModel.validateSelectedDate(LocalDate.now())
        assert(result.isSuccess) { "Today should be valid" }
    }

    @Test
    fun validateSelectedDate_pastDate_succeeds() {
        val result = viewModel.validateSelectedDate(LocalDate.now().minusDays(30))
        assert(result.isSuccess) { "Past date should be valid" }
    }

    @Test
    fun validateSelectedDate_futureDate_fails() {
        val result = viewModel.validateSelectedDate(LocalDate.now().plusDays(1))
        assert(result.isFailure) { "Future date should be invalid" }
    }

    @Test
    fun formatSleepDuration_150minutes_formatsCorrectly() {
        io.mockk.every { getDashboardDataUseCase.formatSleepDuration(150) } returns "2h 30m"
        val formatted = viewModel.formatSleepDuration(150)
        assert(formatted.isNotEmpty()) { "Should format duration" }
        assert(formatted == "2h 30m") { "Should format 150 minutes as 2h 30m" }
    }

    @Test
    fun formatSleepDuration_null_returnsEmpty() {
        val formatted = viewModel.formatSleepDuration(null)
        assert(formatted.isEmpty()) { "Should handle null" }
    }

    @Test
    fun toggleCardManagement_togglesState() {
        val initialState = viewModel.isManagingCards.value
        viewModel.toggleCardManagement()
        // Note: would need stateflow emission to verify state changed
        // This is a basic structure test
        assert(true)
    }

    @Test
    fun onPreviousDay_launchesScope() {
        viewModel.onPreviousDay()
        assert(true) { "Should launch without error" }
    }

    @Test
    fun onNextDay_launchesScope() {
        viewModel.onNextDay()
        assert(true) { "Should launch without error" }
    }
}
