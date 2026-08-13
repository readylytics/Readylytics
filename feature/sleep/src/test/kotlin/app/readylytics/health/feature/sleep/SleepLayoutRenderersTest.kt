package app.readylytics.health.feature.sleep

import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SleepLayoutRenderersTest {
    @Test
    fun `top card data map covers every top card id`() {
        val map = buildSleepTopCardDataMap(SleepUiState(), null)
        assertEquals(SleepTopCardId.entries.toSet(), map.keys)
        SleepTopCardId.entries.forEach { assertNotNull(map[it]) }
    }

    @Test
    fun `metric card data map covers every metric card id`() {
        val map = buildSleepMetricCardDataMap(SleepUiState(), CircadianConsistencyResult.Calibrating, null)
        assertEquals(SleepMetricCardId.entries.toSet(), map.keys)
        SleepMetricCardId.entries.forEach { assertNotNull(map[it]) }
    }

    @Test
    fun `full width sleep top cards set excludes the gauges`() {
        assertEquals(
            setOf(
                SleepTopCardId.SLEEP_BREAKDOWN_BAR,
                SleepTopCardId.SLEEP_STAGES_TIMELINE,
                SleepTopCardId.SLEEP_HR_CHART,
            ),
            SLEEP_TOP_CARD_FULL_WIDTH_IDS,
        )
        assertEquals(
            setOf(SleepTopCardId.SLEEP_SCORE, SleepTopCardId.SLEEP_DURATION_GAUGE),
            SleepTopCardId.entries.toSet() - SLEEP_TOP_CARD_FULL_WIDTH_IDS,
        )
    }
}
