package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailyMetrics
import app.readylytics.health.core.model.domain.model.DailyMetricsMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.util.ResourceProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DashboardScoreMetricPresentationFactoryTest {
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var factory: DashboardScoreMetricPresentationFactory

    @Before
    fun setup() {
        resourceProvider = mockk(relaxed = true)
        every { resourceProvider.getString(any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any(), any(), any(), any()) } returns "mock_str"
        factory = DashboardScoreMetricPresentationFactory(resourceProvider)
    }

    @Test
    fun `builds sleep score, readiness, training readiness, and strain ratio cards`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                sleepScore = 85f,
                strainRatioWorkoutOnly = 1.2f,
            )
        val preferences = UserPreferences()
        val cards =
            factory.build(
                summary = summary,
                metrics = summary?.let { DailyMetricsMapper.toMetrics(it, preferences) },
                preferences = preferences,
                unavailableValueText = "—",
                scoreMaximumText = "100",
                todayStrainIncrease = null,
            )

        assertNotNull(cards[CardId.SLEEP_SCORE])
        assertNotNull(cards[CardId.READINESS])
        assertNotNull(cards[CardId.TRAINING_READINESS])
        assertNotNull(cards[CardId.STRAIN_RATIO])
    }

    @Test
    fun `uses supplied metrics instead of remapping the summary`() {
        val date = LocalDate.of(2026, 11, 5)
        val cards =
            factory.build(
                summary = DailySummary(date = date, sleepScore = 85f),
                metrics = DailyMetrics(date = date, sleepScoreRounded = 42),
                preferences = UserPreferences(),
                unavailableValueText = "—",
                scoreMaximumText = "100",
            )

        assertEquals("42", cards[CardId.SLEEP_SCORE]?.valueText)
    }

    @Test
    fun `builds cards with null summary`() {
        val preferences = UserPreferences()
        val cards =
            factory.build(
                summary = null,
                metrics = null,
                preferences = preferences,
                unavailableValueText = "—",
                scoreMaximumText = "100",
                todayStrainIncrease = null,
            )

        assertNotNull(cards[CardId.SLEEP_SCORE])
        assertNotNull(cards[CardId.READINESS])
        assertNotNull(cards[CardId.TRAINING_READINESS])
        assertNotNull(cards[CardId.STRAIN_RATIO])
    }

    @Test
    fun `strain ratio secondaryText reflects todayStrainIncrease when above threshold`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                strainRatioWorkoutOnly = 1.2f,
            )
        val preferences = UserPreferences()
        every { resourceProvider.getString(any(), any(), any()) } returns "+0.15"

        val cards =
            factory.build(
                summary = summary,
                metrics = summary?.let { DailyMetricsMapper.toMetrics(it, preferences) },
                preferences = preferences,
                unavailableValueText = "—",
                scoreMaximumText = "100",
                todayStrainIncrease = 0.15f,
            )

        assertEquals("+0.15", cards[CardId.STRAIN_RATIO]?.secondaryText)
    }

    @Test
    fun `strain ratio secondaryText reflects no change when increase is small`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                strainRatioWorkoutOnly = 1.2f,
            )
        val preferences = UserPreferences()
        every { resourceProvider.getString(any()) } returns "No change"

        val cards =
            factory.build(
                summary = summary,
                metrics = summary?.let { DailyMetricsMapper.toMetrics(it, preferences) },
                preferences = preferences,
                unavailableValueText = "—",
                scoreMaximumText = "100",
                todayStrainIncrease = 0.001f,
            )

        assertEquals("No change", cards[CardId.STRAIN_RATIO]?.secondaryText)
    }
}
