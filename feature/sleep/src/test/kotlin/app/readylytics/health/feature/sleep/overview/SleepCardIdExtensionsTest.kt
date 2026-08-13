package app.readylytics.health.feature.sleep.overview

import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardId
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepCardIdExtensionsTest {
    @Test
    fun `all SleepTopCardId values map to valid string resource`() {
        SleepTopCardId.entries.forEach { cardId ->
            val resId = cardId.displayNameResource()
            assertTrue("Expected valid res ID for $cardId", resId > 0)
            assertTrue("Expected valid res ID property for $cardId", cardId.displayNameResId > 0)
        }
    }

    @Test
    fun `all SleepMetricCardId values map to valid string resource`() {
        SleepMetricCardId.entries.forEach { cardId ->
            val resId = cardId.displayNameResource()
            assertTrue("Expected valid res ID for $cardId", resId > 0)
            assertTrue("Expected valid res ID property for $cardId", cardId.displayNameResId > 0)
        }
    }

    @Test
    fun `all SleepChartId values map to valid string resource`() {
        SleepChartId.entries.forEach { chartId ->
            val resId = chartId.displayNameResource()
            assertTrue("Expected valid res ID for $chartId", resId > 0)
            assertTrue("Expected valid res ID property for $chartId", chartId.displayNameResId > 0)
        }
    }
}
