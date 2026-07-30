package app.readylytics.health.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMetricScalePreparerTest {
    @Test
    fun `piecewise scale maps anchors and clamps only geometry`() {
        assertEquals(0f, DashboardMetricScalePreparer.piecewiseFraction(10f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(0.5f, DashboardMetricScalePreparer.piecewiseFraction(21.7f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(1f, DashboardMetricScalePreparer.piecewiseFraction(40f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(0f, DashboardMetricScalePreparer.piecewiseFraction(5f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(1f, DashboardMetricScalePreparer.piecewiseFraction(45f, 10f, 21.7f, 40f), 0.001f)
    }

    @Test
    fun `goal retains above target value while marker clamps`() {
        val visual = DashboardMetricScalePreparer.goal(600f, 480f, emptyList())
        assertEquals(600f, visual.rawValue)
        assertEquals(1f, visual.markerFraction)
        assertTrue(visual.isAboveTarget)
    }

    @Test
    fun `missing baseline disables selection but retains current value`() {
        val visual =
            DashboardMetricScalePreparer.personalBaseline(
                value = 41f,
                baseline = null,
                axisMinimumRatio = 0.8f,
                axisMaximumRatio = 1.2f,
                bands = emptyList(),
                baselineReady = false,
            )
        assertEquals(41f, visual.rawValue)
        assertFalse(visual.selectionAvailable)
        assertEquals(DashboardMetricUnavailableReason.BASELINE_NOT_READY, visual.unavailableReason)
    }

    @Test
    fun `score handles values below, inside, and above range`() {
        val visual = DashboardMetricScalePreparer.score(15f, 10f, 20f, emptyList())
        assertEquals(0.5f, visual.markerFraction!!, 0.001f)

        val visualBelow = DashboardMetricScalePreparer.score(5f, 10f, 20f, emptyList())
        assertEquals(0f, visualBelow.markerFraction!!, 0.001f)

        val visualAbove = DashboardMetricScalePreparer.score(25f, 10f, 20f, emptyList())
        assertEquals(1f, visualAbove.markerFraction!!, 0.001f)
    }

    @Test
    fun `goal unavailable states with target null, target 0, or missing value`() {
        val nullTarget = DashboardMetricScalePreparer.goal(10f, null, emptyList())
        assertEquals(DashboardMetricUnavailableReason.MISSING_TARGET, nullTarget.unavailableReason)
        assertFalse(nullTarget.selectionAvailable)
        assertEquals(10f, nullTarget.rawValue)

        val zeroTarget = DashboardMetricScalePreparer.goal(10f, 0f, emptyList())
        assertEquals(DashboardMetricUnavailableReason.MISSING_TARGET, zeroTarget.unavailableReason)
        assertFalse(zeroTarget.selectionAvailable)
        assertEquals(10f, zeroTarget.rawValue)

        val negativeTarget = DashboardMetricScalePreparer.goal(10f, -5f, emptyList())
        assertEquals(DashboardMetricUnavailableReason.MISSING_TARGET, negativeTarget.unavailableReason)
        assertFalse(negativeTarget.selectionAvailable)
        assertEquals(10f, negativeTarget.rawValue)

        val missingValue = DashboardMetricScalePreparer.goal(null, 10f, emptyList())
        assertEquals(DashboardMetricUnavailableReason.MISSING_VALUE, missingValue.unavailableReason)
        assertTrue(missingValue.selectionAvailable)
    }

    @Test
    fun `score with missing value remains selectable and reports em dash reason without a marker`() {
        val visual = DashboardMetricScalePreparer.score(null, 0f, 100f, emptyList())
        assertEquals(DashboardMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        assertEquals(null, visual.markerFraction)
    }

    @Test
    fun `score treats an explicit zero reading as real data distinct from a missing value`() {
        val realZero = DashboardMetricScalePreparer.score(0f, 0f, 100f, emptyList())
        assertEquals(null, realZero.unavailableReason)
        assertEquals(0f, realZero.markerFraction)

        val missing = DashboardMetricScalePreparer.score(null, 0f, 100f, emptyList())
        assertEquals(DashboardMetricUnavailableReason.MISSING_VALUE, missing.unavailableReason)
        assertEquals(null, missing.markerFraction)
    }

    @Test
    fun `baseline present but not yet mature still disables selection and retains value`() {
        val visual =
            DashboardMetricScalePreparer.personalBaseline(
                value = 41f,
                baseline = 50f,
                axisMinimumRatio = 0.8f,
                axisMaximumRatio = 1.2f,
                bands = emptyList(),
                baselineReady = false,
            )
        assertEquals(41f, visual.rawValue)
        assertFalse(visual.selectionAvailable)
        assertEquals(DashboardMetricUnavailableReason.BASELINE_NOT_READY, visual.unavailableReason)
        assertEquals(null, visual.markerFraction)
    }

    @Test
    fun `reference range with an unavailable scale retains the raw value and requires a reason`() {
        val visual =
            DashboardMetricScalePreparer.referenceRange(
                value = 24.3f,
                minimum = 15f,
                midpoint = 21.7f,
                maximum = 35f,
                bands = emptyList(),
                scaleAvailable = false,
                unavailableReason = DashboardMetricUnavailableReason.MISSING_BMI,
            )
        assertEquals(24.3f, visual.rawValue)
        assertFalse(visual.selectionAvailable)
        assertEquals(DashboardMetricUnavailableReason.MISSING_BMI, visual.unavailableReason)
        assertEquals(null, visual.markerFraction)
    }

    @Test
    fun `baseline handles ratios on both sides of 1`() {
        val belowRatio =
            DashboardMetricScalePreparer.personalBaseline(
                value = 40f,
                baseline = 50f,
                axisMinimumRatio = 0.5f,
                axisMaximumRatio = 1.5f,
                bands = emptyList(),
                baselineReady = true,
            )
        assertEquals(0.8f, belowRatio.ratio!!, 0.001f)

        val aboveRatio =
            DashboardMetricScalePreparer.personalBaseline(
                value = 60f,
                baseline = 50f,
                axisMinimumRatio = 0.5f,
                axisMaximumRatio = 1.5f,
                bands = emptyList(),
                baselineReady = true,
            )
        assertEquals(1.2f, aboveRatio.ratio!!, 0.001f)
    }

    @Test
    fun `personal baseline maps an equal value to the midpoint`() {
        val visual =
            DashboardMetricScalePreparer.personalBaseline(
                value = 50f,
                baseline = 50f,
                axisMinimumRatio = 0.8f,
                axisMaximumRatio = 1.2f,
                bands = emptyList(),
                baselineReady = true,
            )

        assertEquals(0.5f, visual.markerFraction!!, 0.001f)
        assertEquals(0.5f, visual.baselineMarkerFraction, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid piecewise anchors throws exception`() {
        DashboardMetricScalePreparer.piecewiseFraction(10f, 20f, 15f, 30f) // min > mid
    }

    @Test
    fun `normalized band ordering preserves statuses`() {
        val bands =
            listOf(
                RawMetricBand(10f, 15f, app.readylytics.health.domain.model.MetricStatus.OPTIMAL),
                RawMetricBand(15f, 20f, app.readylytics.health.domain.model.MetricStatus.NEUTRAL),
            )
        val score = DashboardMetricScalePreparer.score(15f, 10f, 20f, bands)
        assertEquals(0f, score.bands[0].startFraction, 0.001f)
        assertEquals(0.5f, score.bands[0].endFraction, 0.001f)
        assertEquals(app.readylytics.health.domain.model.MetricStatus.OPTIMAL, score.bands[0].status)
        assertEquals(0.5f, score.bands[1].startFraction, 0.001f)
        assertEquals(1f, score.bands[1].endFraction, 0.001f)
        assertEquals(app.readylytics.health.domain.model.MetricStatus.NEUTRAL, score.bands[1].status)
    }
}
