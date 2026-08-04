package app.readylytics.health.feature.dashboard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMetricScalePreparerTest {
    @Test
    fun `piecewise scale maps anchors and clamps only geometry`() {
        assertEquals(0f, UniversalMetricScalePreparer.piecewiseFraction(10f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(0.5f, UniversalMetricScalePreparer.piecewiseFraction(21.7f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(1f, UniversalMetricScalePreparer.piecewiseFraction(40f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(0f, UniversalMetricScalePreparer.piecewiseFraction(5f, 10f, 21.7f, 40f), 0.001f)
        assertEquals(1f, UniversalMetricScalePreparer.piecewiseFraction(45f, 10f, 21.7f, 40f), 0.001f)
    }

    @Test
    fun `goal retains above target value while marker clamps`() {
        val visual = UniversalMetricScalePreparer.goal(600f, 480f)
        assertEquals(600f, visual.rawValue)
        assertEquals(1f, visual.markerFraction)
        assertTrue(visual.isAboveTarget)
    }

    @Test
    fun `missing baseline disables selection but retains current value`() {
        val visual =
            UniversalMetricScalePreparer.personalBaseline(
                value = 41f,
                baseline = null,
                axisMinimumRatio = 0.8f,
                axisMaximumRatio = 1.2f,
                baselineReady = false,
            )
        assertEquals(41f, visual.rawValue)
        assertFalse(visual.selectionAvailable)
        assertEquals(UniversalMetricUnavailableReason.BASELINE_NOT_READY, visual.unavailableReason)
    }

    @Test
    fun `score handles values below, inside, and above range`() {
        val visual = UniversalMetricScalePreparer.score(15f, 10f, 20f)
        assertEquals(0.5f, visual.markerFraction!!, 0.001f)

        val visualBelow = UniversalMetricScalePreparer.score(5f, 10f, 20f)
        assertEquals(0f, visualBelow.markerFraction!!, 0.001f)

        val visualAbove = UniversalMetricScalePreparer.score(25f, 10f, 20f)
        assertEquals(1f, visualAbove.markerFraction!!, 0.001f)
    }

    @Test
    fun `goal unavailable states with target null, target 0, or missing value`() {
        val nullTarget = UniversalMetricScalePreparer.goal(10f, null)
        assertEquals(UniversalMetricUnavailableReason.MISSING_TARGET, nullTarget.unavailableReason)
        assertFalse(nullTarget.selectionAvailable)
        assertEquals(10f, nullTarget.rawValue)

        val zeroTarget = UniversalMetricScalePreparer.goal(10f, 0f)
        assertEquals(UniversalMetricUnavailableReason.MISSING_TARGET, zeroTarget.unavailableReason)
        assertFalse(zeroTarget.selectionAvailable)
        assertEquals(10f, zeroTarget.rawValue)

        val negativeTarget = UniversalMetricScalePreparer.goal(10f, -5f)
        assertEquals(UniversalMetricUnavailableReason.MISSING_TARGET, negativeTarget.unavailableReason)
        assertFalse(negativeTarget.selectionAvailable)
        assertEquals(10f, negativeTarget.rawValue)

        val missingValue = UniversalMetricScalePreparer.goal(null, 10f)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, missingValue.unavailableReason)
        assertTrue(missingValue.selectionAvailable)
    }

    @Test
    fun `score with missing value remains selectable and reports em dash reason without a marker`() {
        val visual = UniversalMetricScalePreparer.score(null, 0f, 100f)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        assertEquals(null, visual.markerFraction)
    }

    @Test
    fun `score treats an explicit zero reading as real data distinct from a missing value`() {
        val realZero = UniversalMetricScalePreparer.score(0f, 0f, 100f)
        assertEquals(null, realZero.unavailableReason)
        assertEquals(0f, realZero.markerFraction)

        val missing = UniversalMetricScalePreparer.score(null, 0f, 100f)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, missing.unavailableReason)
        assertEquals(null, missing.markerFraction)
    }

    @Test
    fun `baseline present but not yet mature still disables selection and retains value`() {
        val visual =
            UniversalMetricScalePreparer.personalBaseline(
                value = 41f,
                baseline = 50f,
                axisMinimumRatio = 0.8f,
                axisMaximumRatio = 1.2f,
                baselineReady = false,
            )
        assertEquals(41f, visual.rawValue)
        assertFalse(visual.selectionAvailable)
        assertEquals(UniversalMetricUnavailableReason.BASELINE_NOT_READY, visual.unavailableReason)
        assertEquals(null, visual.markerFraction)
    }

    @Test
    fun `reference range with an unavailable scale retains the raw value and requires a reason`() {
        val visual =
            UniversalMetricScalePreparer.referenceRange(
                value = 24.3f,
                minimum = 15f,
                midpoint = 21.7f,
                maximum = 35f,
                scaleAvailable = false,
                unavailableReason = UniversalMetricUnavailableReason.MISSING_BMI,
            )
        assertEquals(24.3f, visual.rawValue)
        assertFalse(visual.selectionAvailable)
        assertEquals(UniversalMetricUnavailableReason.MISSING_BMI, visual.unavailableReason)
        assertEquals(null, visual.markerFraction)
    }

    @Test
    fun `baseline handles ratios on both sides of 1`() {
        val belowRatio =
            UniversalMetricScalePreparer.personalBaseline(
                value = 40f,
                baseline = 50f,
                axisMinimumRatio = 0.5f,
                axisMaximumRatio = 1.5f,
                baselineReady = true,
            )
        assertEquals(0.8f, belowRatio.ratio!!, 0.001f)

        val aboveRatio =
            UniversalMetricScalePreparer.personalBaseline(
                value = 60f,
                baseline = 50f,
                axisMinimumRatio = 0.5f,
                axisMaximumRatio = 1.5f,
                baselineReady = true,
            )
        assertEquals(1.2f, aboveRatio.ratio!!, 0.001f)
    }

    @Test
    fun `personal baseline maps an equal value to the midpoint`() {
        val visual =
            UniversalMetricScalePreparer.personalBaseline(
                value = 50f,
                baseline = 50f,
                axisMinimumRatio = 0.8f,
                axisMaximumRatio = 1.2f,
                baselineReady = true,
            )

        assertEquals(0.5f, visual.markerFraction!!, 0.001f)
        assertEquals(0.5f, visual.baselineMarkerFraction, 0.001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid piecewise anchors throws exception`() {
        UniversalMetricScalePreparer.piecewiseFraction(10f, 20f, 15f, 30f) // min > mid
    }

    @Test
    fun `scale preparers retain geometry without status bands`() {
        val score = UniversalMetricScalePreparer.score(15f, 10f, 20f)
        val goal = UniversalMetricScalePreparer.goal(600f, 480f)
        val baseline = UniversalMetricScalePreparer.personalBaseline(50f, 50f, 0.8f, 1.2f, true)
        val reference = UniversalMetricScalePreparer.referenceRange(21.7f, 15f, 21.7f, 35f, true, null)

        assertEquals(0.5f, score.markerFraction!!, 0.001f)
        assertEquals(1f, goal.markerFraction!!, 0.001f)
        assertEquals(0.5f, baseline.markerFraction!!, 0.001f)
        assertEquals(0.5f, reference.markerFraction!!, 0.001f)
    }
}
