package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMetricPresentationFactoryTest : DashboardMetricPresentationFactoryTestBase() {
    @Test
    fun `build presents residual fatigue with score visual and optimal status when below 30`() {
        val summary = summary(residualFatigue = 18.5f)
        val preferences = preferences(residualFatigueEnabled = true, residualFatigueHalfLifeHours = 24f)
        val map =
            factory.build(
                summary = summary,
                preferences = preferences,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
            )

        val presentation = map[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentation)
        assertEquals("18.5", presentation?.valueText)
        assertEquals(MetricStatus.OPTIMAL, presentation?.status)
        assertEquals("Half-life: 24h", presentation?.secondaryText)
        assertTrue(presentation?.visual is UniversalMetricVisual.Score)
        val score = presentation?.visual as UniversalMetricVisual.Score
        assertEquals(18.5f, score.rawValue)
        assertEquals(0f, score.minValue)
        assertEquals(100f, score.maxValue)
        assertEquals(0.185f, score.markerFraction)
        assertNull(score.unavailableReason)
    }

    @Test
    fun `build presents neutral status when residual fatigue is between 30 and 70`() {
        val summary = summary(residualFatigue = 50.0f)
        val preferences = preferences(residualFatigueEnabled = true, residualFatigueHalfLifeHours = 36f)
        val map =
            factory.build(
                summary = summary,
                preferences = preferences,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
            )

        val presentation = map[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentation)
        assertEquals("50.0", presentation?.valueText)
        assertEquals(MetricStatus.NEUTRAL, presentation?.status)
        assertEquals("Half-life: 36h", presentation?.secondaryText)
    }

    @Test
    fun `build presents warning status when residual fatigue is above 70`() {
        val summary = summary(residualFatigue = 85.0f)
        val preferences = preferences(residualFatigueEnabled = true, residualFatigueHalfLifeHours = 48f)
        val map =
            factory.build(
                summary = summary,
                preferences = preferences,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
            )

        val presentation = map[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentation)
        assertEquals("85.0", presentation?.valueText)
        assertEquals(MetricStatus.WARNING, presentation?.status)
        assertEquals("Half-life: 48h", presentation?.secondaryText)
    }

    @Test
    fun `build presents missing value when residual fatigue is disabled or null`() {
        val summary = summary(residualFatigue = 50.0f)
        val preferencesDisabled = preferences(residualFatigueEnabled = false)
        val mapDisabled =
            factory.build(
                summary = summary,
                preferences = preferencesDisabled,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
            )

        val presentationDisabled = mapDisabled[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentationDisabled)
        assertEquals(MetricStatus.NO_DATA, presentationDisabled?.status)
        assertEquals("—", presentationDisabled?.valueText)
        val scoreDisabled = presentationDisabled?.visual as UniversalMetricVisual.Score
        assertNull(scoreDisabled.rawValue)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, scoreDisabled.unavailableReason)

        val summaryNull = summary(residualFatigue = null)
        val preferencesEnabled = preferences(residualFatigueEnabled = true)
        val mapNull =
            factory.build(
                summary = summaryNull,
                preferences = preferencesEnabled,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
            )

        val presentationNull = mapNull[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentationNull)
        assertEquals(MetricStatus.NO_DATA, presentationNull?.status)
        assertEquals("—", presentationNull?.valueText)
    }

    @Test
    fun `build presents residual fatigue with real tooltip resource`() {
        stubTooltips()
        val summary = summary(residualFatigue = 25.0f)
        val preferences = preferences(residualFatigueEnabled = true)
        val map =
            factory.build(
                summary = summary,
                preferences = preferences,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
            )

        val presentation = map[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentation)
        assertEquals("tooltip residual fatigue", presentation?.tooltip)
    }
}
