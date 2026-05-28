package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GetDashboardDataUseCaseTest {
    private lateinit var getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase
    private lateinit var resourceProvider: com.gregor.lauritz.healthdashboard.domain.util.ResourceProvider
    private lateinit var useCase: GetDashboardDataUseCase

    @Before
    fun setUp() {
        getWorkoutMetricsUseCase = mockk(relaxed = true)
        resourceProvider = mockk(relaxed = true)
        useCase =
            GetDashboardDataUseCase(
                resourceProvider = resourceProvider,
                getWorkoutMetricsUseCase = getWorkoutMetricsUseCase,
            )
    }

    @Test
    fun invoke_validInputs_succeeds() {
        val summary = mockk<DailySummary>(relaxed = true)
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        assert(result.isSuccess) { "Should succeed with valid inputs" }
    }

    @Test
    fun invoke_nullSummary_succeeds() {
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = null,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        assert(result.isSuccess) { "Should handle null summary" }
    }

    @Test
    fun invoke_returnsCardDataMap() {
        val summary = mockk<DailySummary>(relaxed = true)
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        assert(result.getOrNull()?.cardDataMap != null) { "Should return card data map" }
    }
}
