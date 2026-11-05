package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardSleepRhythmMetricPresentationFactoryTest {
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var factory: DashboardSleepRhythmMetricPresentationFactory

    @Before
    fun setup() {
        resourceProvider = mockk(relaxed = true)
        every { resourceProvider.getString(any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any(), any(), any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any(), any(), any(), any()) } returns "mock_str"

        every { resourceProvider.getString(CoreUiR.string.card_title_sleep_efficiency) } returns "Sleep Efficiency"
        every { resourceProvider.getString(CoreUiR.string.card_tooltip_sleep_efficiency) } returns
            "Sleep efficiency tooltip"
        every { resourceProvider.getString(DashboardR.string.card_title_circadian_consistency) } returns
            "Circadian Consistency"
        every { resourceProvider.getString(CoreUiR.string.tooltip_circadian_score) } returns
            "Circadian score tooltip"
        every { resourceProvider.getString(CoreUiR.string.score_maximum) } returns "100"
        every { resourceProvider.getString(CoreUiR.string.metric_value_unavailable) } returns "—"
        every { resourceProvider.getString(CoreUiR.string.metric_unavailable_missing_value) } returns "Missing data"

        every { resourceProvider.getString(CoreUiR.string.metric_status_optimal) } returns "Optimal"
        every { resourceProvider.getString(CoreUiR.string.metric_status_neutral) } returns "Neutral"
        every { resourceProvider.getString(CoreUiR.string.metric_status_warning) } returns "Warning"
        every { resourceProvider.getString(CoreUiR.string.metric_status_poor) } returns "Poor"
        every { resourceProvider.getString(CoreUiR.string.metric_status_calibrating) } returns "Calibrating"

        every { resourceProvider.getString(DashboardR.string.semantics_unavailable_format, *anyVararg()) } answers {
            val args = it.invocation.args[1] as Array<*>
            "${args[0]}: unavailable, ${args[1]}"
        }

        every { resourceProvider.getString(DashboardR.string.semantics_value_note_format, *anyVararg()) } answers {
            val args = it.invocation.args[1] as Array<*>
            "${args[0]}: ${args[1]}, ${args[2]}"
        }

        every { resourceProvider.getString(DashboardR.string.semantics_score_format, *anyVararg()) } answers {
            val args = it.invocation.args[1] as Array<*>
            "${args[0]}: ${args[1]} of ${args[2]}, ${args[3]}"
        }

        factory = DashboardSleepRhythmMetricPresentationFactory(resourceProvider)
    }

    @Test
    fun `build returns presentation for sleep efficiency and circadian consistency`() {
        val session = SleepSessionSummary(efficiency = 92f, startTime = 0L, endTime = 0L)
        val circadian =
            CircadianConsistencyResult.Ready(
                score = 88f,
                medianBedtimeMinutes = 0,
                medianWakeMinutes = 0,
                thresholdMinutes = 0,
                latestBedtimeOffsetMinutes = 0,
            )

        val cards = factory.build(lastSleepSession = session, circadianResult = circadian)

        assertEquals(2, cards.size)
        assertNotNull(cards[CardId.SLEEP_EFFICIENCY])
        assertNotNull(cards[CardId.CIRCADIAN_CONSISTENCY])
    }

    @Test
    fun `sleep efficiency presentation formats percentage and score visual`() {
        val session = SleepSessionSummary(efficiency = 95.13f, startTime = 0L, endTime = 0L)

        val cards = factory.build(lastSleepSession = session, circadianResult = null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score

        assertEquals("Sleep Efficiency", presentation.title)
        assertEquals("95%", presentation.valueText)
        assertEquals("", presentation.unitText)
        assertNull(presentation.secondaryText)
        assertEquals(MetricStatus.OPTIMAL, presentation.status)
        assertEquals("Sleep efficiency tooltip", presentation.tooltip)
        assertEquals(95.13f, visual.rawValue)
        assertEquals(0f, visual.minValue)
        assertEquals(100f, visual.maxValue)
        assertEquals("Sleep Efficiency: 95%, Optimal", presentation.accessibilityDescription)
    }

    @Test
    fun `sleep efficiency normalizes legacy fractional ratio to percentage`() {
        val session = SleepSessionSummary(efficiency = 0.9f, startTime = 0L, endTime = 0L)

        val cards = factory.build(lastSleepSession = session, circadianResult = null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score

        assertEquals("90%", presentation.valueText)
        assertEquals(MetricStatus.OPTIMAL, presentation.status)
        assertEquals(90f, visual.rawValue)
    }

    @Test
    fun `sleep efficiency formats genuine zero percent`() {
        val session = SleepSessionSummary(efficiency = 0f, startTime = 0L, endTime = 0L)

        val cards = factory.build(lastSleepSession = session, circadianResult = null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score

        assertEquals("0%", presentation.valueText)
        assertEquals(0f, visual.markerFraction)
        assertNull(visual.unavailableReason)
    }

    @Test
    fun `sleep efficiency handles null session as missing value`() {
        val cards = factory.build(lastSleepSession = null, circadianResult = null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score

        assertEquals("—", presentation.valueText)
        assertEquals(MetricStatus.CALIBRATING, presentation.status)
        assertNull(visual.rawValue)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        assertEquals("Sleep Efficiency: unavailable, Missing data", presentation.accessibilityDescription)
    }

    @Test
    fun `sleep efficiency handles non-finite values as missing value`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { nonFinite ->
            val session = SleepSessionSummary(efficiency = nonFinite, startTime = 0L, endTime = 0L)
            val cards = factory.build(lastSleepSession = session, circadianResult = null)
            val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
            val visual = presentation.visual as UniversalMetricVisual.Score

            assertEquals("—", presentation.valueText)
            assertEquals(MetricStatus.CALIBRATING, presentation.status)
            assertNull(visual.rawValue)
            assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        }
    }

    @Test
    fun `circadian consistency formats percentage and score visual when ready`() {
        val circadian =
            CircadianConsistencyResult.Ready(
                score = 95.4f,
                medianBedtimeMinutes = 0,
                medianWakeMinutes = 0,
                thresholdMinutes = 0,
                latestBedtimeOffsetMinutes = 0,
            )

        val cards = factory.build(lastSleepSession = null, circadianResult = circadian)
        val presentation = cards.getValue(CardId.CIRCADIAN_CONSISTENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score

        assertEquals("Circadian Consistency", presentation.title)
        assertEquals("95%", presentation.valueText)
        assertEquals("", presentation.unitText)
        assertNull(presentation.secondaryText)
        assertEquals(MetricStatus.OPTIMAL, presentation.status)
        assertEquals("Circadian score tooltip", presentation.tooltip)
        assertEquals(95.4f, visual.rawValue)
        assertEquals(0f, visual.minValue)
        assertEquals(100f, visual.maxValue)
        assertEquals("Circadian Consistency: 95 of 100, Optimal", presentation.accessibilityDescription)
    }

    @Test
    fun `circadian consistency handles null result as missing value`() {
        val cards = factory.build(lastSleepSession = null, circadianResult = null)
        val presentation = cards.getValue(CardId.CIRCADIAN_CONSISTENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score

        assertEquals("—", presentation.valueText)
        assertEquals(MetricStatus.CALIBRATING, presentation.status)
        assertNull(visual.rawValue)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        assertEquals("Circadian Consistency: unavailable, Missing data", presentation.accessibilityDescription)
    }

    @Test
    fun `circadian consistency handles non-ready result as calibrating`() {
        listOf(
            CircadianConsistencyResult.Calibrating,
            CircadianConsistencyResult.MissingData,
        ).forEach { result ->
            val cards = factory.build(lastSleepSession = null, circadianResult = result)
            val presentation = cards.getValue(CardId.CIRCADIAN_CONSISTENCY)
            val visual = presentation.visual as UniversalMetricVisual.Score

            assertEquals("—", presentation.valueText)
            assertEquals(MetricStatus.CALIBRATING, presentation.status)
            assertNull(visual.rawValue)
            assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        }
    }
}
