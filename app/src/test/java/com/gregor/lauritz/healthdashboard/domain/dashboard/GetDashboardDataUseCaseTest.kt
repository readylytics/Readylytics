package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.getOrNull
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
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

    @Test
    fun invoke_withOptimalSpo2_returnsOptimalSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 98.5f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("99", card?.value)
        assertEquals(MetricStatus.OPTIMAL, card?.status)
    }

    @Test
    fun invoke_withNormalSpo2_returnsNormalSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 95.2f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("95", card?.value)
        assertEquals(MetricStatus.NEUTRAL, card?.status)
    }

    @Test
    fun invoke_withWarningSpo2_returnsWarningSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 91.6f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("92", card?.value)
        assertEquals(MetricStatus.WARNING, card?.status)
    }

    @Test
    fun invoke_withPoorSpo2_returnsPoorSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns 88.0f
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("88", card?.value)
        assertEquals(MetricStatus.POOR, card?.status)
    }

    @Test
    fun invoke_withNullSpo2_returnsCalibratingSpo2Card() {
        val summary =
            mockk<DailySummary>(relaxed = true) {
                every { avgSleepingSpo2 } returns null
            }
        val prefs = mockk<UserPreferences>(relaxed = true)
        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = LocalDate.now(),
                lastSleepSession = null,
                paiSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("—", card?.value)
        assertEquals(MetricStatus.CALIBRATING, card?.status)
    }
}
