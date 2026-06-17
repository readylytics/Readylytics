package app.readylytics.health.domain.dashboard

import app.readylytics.health.R
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.ui.components.GaugeComparisonTone
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GetDashboardDataUseCaseTest {
    private lateinit var getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase
    private lateinit var resourceProvider: app.readylytics.health.domain.util.ResourceProvider
    private lateinit var useCase: GetDashboardDataUseCase

    @Before
    fun setUp() {
        getWorkoutMetricsUseCase = mockk(relaxed = true)
        resourceProvider = FakeDashboardResourceProvider()
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
                rasSummaries = emptyList(),
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
                rasSummaries = emptyList(),
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
                rasSummaries = emptyList(),
            )
        assert(result.getOrNull()?.cardDataMap != null) { "Should return card data map" }
    }

    @Test
    fun invoke_scoreCardsUseCanonicalRoundedDisplayValues() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 6, 9),
                sleepScore = 79.5f,
                // Default strainLoadSourceMode is WORKOUT_ONLY.
                readinessWorkoutOnly = 72.5f,
                strainRatioWorkoutOnly = 0.365f,
            )
        val prefs = UserPreferences()
        every { getWorkoutMetricsUseCase(summary, any()) } answers {
            GetWorkoutMetricsUseCase(resourceProvider)(summary, secondArg())
        }

        val result =
            useCase(
                summary = summary,
                prefs = prefs,
                date = summary.date,
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )

        val cards = result.getOrNull()?.cardDataMap.orEmpty()
        assertEquals("80", cards[CardId.SLEEP_SCORE]?.value)
        assertEquals("73", cards[CardId.READINESS]?.value)
        assertEquals("0.37", cards[CardId.STRAIN_RATIO]?.value)
    }

    @Test
    fun invoke_scoreCardsCompareAgainstPreviousDayWhenAvailable() {
        val date = LocalDate.of(2026, 6, 9)
        val summary =
            DailySummary(
                date = date,
                sleepScore = 79.5f,
                readinessWorkoutOnly = 72.5f,
            )
        val previousSummary =
            DailySummary(
                date = date.minusDays(1),
                sleepScore = 75.1f,
                readinessWorkoutOnly = 76.4f,
            )

        val result =
            useCase(
                summary = summary,
                prefs = UserPreferences(rasSourceMode = LoadSourceMode.WORKOUT_ONLY),
                date = date,
                lastSleepSession = null,
                rasSummaries = emptyList(),
                previousSummary = previousSummary,
            )

        val cards = result.getOrNull()?.cardDataMap.orEmpty()
        assertEquals("↑ 5 vs yesterday", cards[CardId.SLEEP_SCORE]?.comparisonText)
        assertEquals(GaugeComparisonTone.POSITIVE, cards[CardId.SLEEP_SCORE]?.comparisonTone)
        assertEquals("↓ 3 vs yesterday", cards[CardId.READINESS]?.comparisonText)
        assertEquals(GaugeComparisonTone.NEGATIVE, cards[CardId.READINESS]?.comparisonTone)
    }

    @Test
    fun invoke_scoreCardsHideComparisonWhenPreviousDayMissing() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 6, 9),
                sleepScore = 79.5f,
                readinessWorkoutOnly = 72.5f,
            )

        val result =
            useCase(
                summary = summary,
                prefs = UserPreferences(),
                date = summary.date,
                lastSleepSession = null,
                rasSummaries = emptyList(),
                previousSummary = null,
            )

        val cards = result.getOrNull()?.cardDataMap.orEmpty()
        assertNull(cards[CardId.SLEEP_SCORE]?.comparisonText)
        assertNull(cards[CardId.READINESS]?.comparisonText)
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
                rasSummaries = emptyList(),
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
                rasSummaries = emptyList(),
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
                rasSummaries = emptyList(),
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
                rasSummaries = emptyList(),
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
                rasSummaries = emptyList(),
            )
        val card = result.getOrNull()?.cardDataMap?.get(CardId.OXYGEN_SATURATION)
        assert(card != null)
        assertEquals("—", card?.value)
        assertEquals(MetricStatus.CALIBRATING, card?.status)
    }

    private class FakeDashboardResourceProvider : ResourceProvider {
        override fun getString(resId: Int): String = "res-$resId"

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String =
            when (resId) {
                R.string.dashboard_comparison_up_vs_yesterday -> "↑ ${formatArgs[0]} vs yesterday"
                R.string.dashboard_comparison_down_vs_yesterday -> "↓ ${formatArgs[0]} vs yesterday"
                R.string.dashboard_comparison_same_vs_yesterday -> "No change vs yesterday"
                else -> getString(resId)
            }
    }
}
