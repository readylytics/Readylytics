package app.readylytics.health.core.ui.components.metriccard

import app.readylytics.health.core.model.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class UniversalMetricPresentationColorStatusTest {
    @Test
    fun `value awaiting its baseline is tinted neutral but keeps calibrating semantics`() {
        val presentation = presentation(MetricStatus.CALIBRATING, baselineVisual(value = 47f, baseline = null))

        assertEquals(MetricStatus.NEUTRAL, presentation.colorStatus)
        assertEquals(MetricStatus.CALIBRATING, presentation.status)
    }

    @Test
    fun `card without a value keeps the empty-card tone`() {
        val presentation = presentation(MetricStatus.CALIBRATING, baselineVisual(value = null, baseline = null))

        assertEquals(MetricStatus.CALIBRATING, presentation.colorStatus)
    }

    @Test
    fun `classified readings keep their own tone`() {
        MetricStatus.entries
            .filter { it != MetricStatus.CALIBRATING }
            .forEach { status ->
                val presentation = presentation(status, baselineVisual(value = 47f, baseline = null))
                assertEquals(status, presentation.colorStatus)
            }
        assertEquals(
            MetricStatus.OPTIMAL,
            presentation(MetricStatus.OPTIMAL, baselineVisual(value = 47f, baseline = 50f)).colorStatus,
        )
    }

    @Test
    fun `calibrating non-baseline visuals keep the empty-card tone`() {
        val score = UniversalMetricScalePreparer.score(value = 70f, minimum = 0f, maximum = 100f)

        assertEquals(MetricStatus.CALIBRATING, presentation(MetricStatus.CALIBRATING, score).colorStatus)
        assertEquals(
            MetricStatus.CALIBRATING,
            presentation(MetricStatus.CALIBRATING, UniversalMetricVisual.ValueOnly).colorStatus,
        )
    }

    private fun baselineVisual(
        value: Float?,
        baseline: Float?,
    ) = UniversalMetricScalePreparer.personalBaseline(
        value = value,
        baseline = baseline,
        axisMinimumRatio = 0.9f,
        axisMaximumRatio = 1.1f,
        baselineReady = baseline != null,
    )

    private fun presentation(
        status: MetricStatus,
        visual: UniversalMetricVisual,
    ) = UniversalMetricPresentation(
        title = "Resting HR",
        valueText = "47",
        unitText = "bpm",
        secondaryText = null,
        status = status,
        tooltip = "",
        accessibilityDescription = "",
        visual = visual,
    )
}
