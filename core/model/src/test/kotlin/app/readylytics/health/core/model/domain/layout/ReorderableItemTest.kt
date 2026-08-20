package app.readylytics.health.core.model.domain.layout

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.sleep.SleepChartConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepChartId
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsChartId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReorderableItemTest {
    @Test
    fun `card configuration exposes cardId as id`() {
        val config = CardConfiguration(CardId.HRV, isVisible = false, position = 3)
        assertEquals(CardId.HRV, config.id)
        assertFalse(config.isVisible)
        assertEquals(3, config.position)
    }

    @Test
    fun `vitals chart configuration exposes chartId as id`() {
        val config = VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 1)
        assertEquals(VitalsChartId.HRV_TREND, config.id)
    }

    @Test
    fun `sleep top card configuration exposes cardId as id`() {
        val config = SleepTopCardConfiguration(SleepTopCardId.SLEEP_SCORE, isVisible = true, position = 0)
        assertEquals(SleepTopCardId.SLEEP_SCORE, config.id)
    }

    @Test
    fun `sleep chart configuration exposes chartId as id`() {
        val config = SleepChartConfiguration(SleepChartId.SLEEP_DURATION_TREND, isVisible = true, position = 0)
        assertEquals(SleepChartId.SLEEP_DURATION_TREND, config.id)
    }

    @Test
    fun `sleep metric card configuration exposes cardId as id`() {
        val config = SleepMetricCardConfiguration(SleepMetricCardId.DEEP_SLEEP, isVisible = true, position = 2)
        assertEquals(SleepMetricCardId.DEEP_SLEEP, config.id)
    }
}
