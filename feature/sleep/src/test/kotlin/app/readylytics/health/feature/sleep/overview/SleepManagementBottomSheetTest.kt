package app.readylytics.health.feature.sleep.overview

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepManagementBottomSheetTest {
    @Test
    fun `top card configurations sort by position correctly`() {
        val configs =
            listOf(
                SleepTopCardConfiguration(cardId = SleepTopCardId.SLEEP_STAGES_TIMELINE, position = 2),
                SleepTopCardConfiguration(cardId = SleepTopCardId.SLEEP_SCORE, position = 0),
                SleepTopCardConfiguration(cardId = SleepTopCardId.SLEEP_DURATION_GAUGE, position = 1),
            )

        val sorted = configs.sortedBy { it.position }
        assertEquals(SleepTopCardId.SLEEP_SCORE, sorted[0].cardId)
        assertEquals(SleepTopCardId.SLEEP_DURATION_GAUGE, sorted[1].cardId)
        assertEquals(SleepTopCardId.SLEEP_STAGES_TIMELINE, sorted[2].cardId)
    }

    @Test
    fun `metric card display mode modification preserves card identity`() {
        val card =
            SleepMetricCardConfiguration(
                cardId = SleepMetricCardId.CIRCADIAN_CONSISTENCY,
                position = 0,
                isVisible = true,
            )
        val updated = card.copy(requestedDisplayMode = DashboardCardDisplayMode.GAUGE)

        assertEquals(SleepMetricCardId.CIRCADIAN_CONSISTENCY, updated.cardId)
        assertTrue(updated.isVisible)
        assertEquals(DashboardCardDisplayMode.GAUGE, updated.requestedDisplayMode)
    }

    @Test
    fun `chart configuration visibility toggle works correctly`() {
        val chart = SleepChartConfiguration(chartId = SleepChartId.SLEEP_DURATION_TREND, isVisible = true, position = 0)
        val hidden = chart.copy(isVisible = false)

        assertFalse(hidden.isVisible)
        assertEquals(SleepChartId.SLEEP_DURATION_TREND, hidden.chartId)
    }
}
