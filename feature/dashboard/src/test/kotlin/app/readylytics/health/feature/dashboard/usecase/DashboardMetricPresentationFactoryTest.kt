package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMetricPresentationFactoryTest : DashboardMetricPresentationFactoryTestBase() {
    @Test
    fun `residual fatigue card prefers the live value over the persisted snapshot`() {
        val summary = summary(residualFatigue = 60.7f)
        val preferences =
            preferences(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 24f,
                residualFatigueGain = 1f,
            )

        val map =
            factory.build(
                summary = summary,
                preferences = preferences,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
                liveResidualFatigue = LiveResidualFatigue.Value(97.8f),
            )

        val presentation = map[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentation)
        assertEquals("97.8", presentation?.valueText)
    }

    // Regression: Unavailable must not fall through to the snapshot. The live gate blocks on any
    // retained workout ending before now; the snapshot's gate only sees workouts starting before
    // today, so a workout logged today with no backfilled TRIMP leaves the snapshot non-null and
    // silently understated. Showing it would defeat the gate.
    @Test
    fun `residual fatigue card reports NO_DATA when the live value is unavailable`() {
        val summary = summary(residualFatigue = 60.7f)
        val preferences =
            preferences(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 24f,
                residualFatigueGain = 1f,
            )

        val map =
            factory.build(
                summary = summary,
                preferences = preferences,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
                liveResidualFatigue = LiveResidualFatigue.Unavailable,
            )

        val presentation = map[CardId.RESIDUAL_FATIGUE]
        assertNotNull(presentation)
        assertEquals(MetricStatus.NO_DATA, presentation?.status)
        assertNotEquals("60.7", presentation?.valueText)
    }

    @Test
    fun `residual fatigue card falls back to the persisted snapshot when not applicable`() {
        val summary = summary(residualFatigue = 60.7f)
        val preferences =
            preferences(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 24f,
                residualFatigueGain = 1f,
            )

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
        assertEquals("60.7", presentation?.valueText)
    }

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
    fun `residual fatigue thresholds and gauge scale with the configured gain`() {
        // At gain 5.0 the metric is produced on a five-times-larger scale, so 120 is the same
        // relative load that 24 would be at gain 1.0 — Optimal, not Warning, and nowhere near the
        // top of the gauge.
        val summary = summary(residualFatigue = 120f)
        val preferences =
            preferences(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 24f,
                residualFatigueGain = 5f,
            )

        val presentation =
            factory
                .build(
                    summary = summary,
                    preferences = preferences,
                    lastSleepSession = null,
                    circadianResult = null,
                    heartRateSummary = null,
                )[CardId.RESIDUAL_FATIGUE]

        assertEquals(MetricStatus.OPTIMAL, presentation?.status)
        val score = presentation?.visual as UniversalMetricVisual.Score
        assertEquals(500f, score.maxValue)
        assertEquals(0.24f, score.markerFraction)
    }

    @Test
    fun `residual fatigue at low gain is not forced to optimal by fixed thresholds`() {
        // At gain 0.1 a value of 9 is 90% of the gauge and firmly Warning; fixed 30/70 cut-points
        // would have called it Optimal and pinned the gauge to ~9%.
        val summary = summary(residualFatigue = 9f)
        val preferences =
            preferences(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 24f,
                residualFatigueGain = 0.1f,
            )

        val presentation =
            factory
                .build(
                    summary = summary,
                    preferences = preferences,
                    lastSleepSession = null,
                    circadianResult = null,
                    heartRateSummary = null,
                )[CardId.RESIDUAL_FATIGUE]

        assertEquals(MetricStatus.WARNING, presentation?.status)
        val score = presentation?.visual as UniversalMetricVisual.Score
        assertEquals(10f, score.maxValue, 0.0001f)
        assertEquals(0.9f, score.markerFraction!!, 0.0001f)
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
