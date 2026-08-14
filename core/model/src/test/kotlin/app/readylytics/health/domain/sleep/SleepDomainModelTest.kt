package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepDomainModelTest {

    @Test
    fun sleepTopCardId_containsExpectedEnumValues() {
        val expected = setOf(
            "SLEEP_SCORE",
            "SLEEP_DURATION_GAUGE",
            "SLEEP_BREAKDOWN_BAR",
            "SLEEP_STAGES_TIMELINE",
            "SLEEP_HR_CHART",
        )
        val actual = SleepTopCardId.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun sleepChartId_containsExpectedEnumValues() {
        val expected = setOf("SLEEP_DURATION_TREND")
        val actual = SleepChartId.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun sleepMetricCardId_containsExpectedEnumValues() {
        val expected = setOf(
            "CIRCADIAN_CONSISTENCY",
            "SLEEP_EFFICIENCY",
            "DEEP_SLEEP",
            "REM_SLEEP",
            "NAP_DURATION",
            "NAP_COUNT",
        )
        val actual = SleepMetricCardId.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun sleepTopCardConfiguration_defaultsAndSerialization() {
        val config = SleepTopCardConfiguration(
            cardId = SleepTopCardId.SLEEP_SCORE,
            isVisible = true,
            position = 0,
        )
        assertEquals(SleepTopCardId.SLEEP_SCORE, config.cardId)
        assertTrue(config.isVisible)
        assertEquals(0, config.position)
        assertNull(config.requestedDisplayMode)

        val json = Json.encodeToString(config)
        val decoded = Json.decodeFromString<SleepTopCardConfiguration>(json)
        assertEquals(config, decoded)
    }

    @Test
    fun sleepChartConfiguration_defaultsAndSerialization() {
        val config = SleepChartConfiguration(
            chartId = SleepChartId.SLEEP_DURATION_TREND,
            isVisible = true,
            position = 0,
        )
        assertEquals(SleepChartId.SLEEP_DURATION_TREND, config.chartId)
        assertTrue(config.isVisible)
        assertEquals(0, config.position)

        val json = Json.encodeToString(config)
        val decoded = Json.decodeFromString<SleepChartConfiguration>(json)
        assertEquals(config, decoded)
    }

    @Test
    fun sleepMetricCardConfiguration_defaultsAndSerialization() {
        val config = SleepMetricCardConfiguration(
            cardId = SleepMetricCardId.SLEEP_EFFICIENCY,
            isVisible = false,
            position = 2,
            requestedDisplayMode = DashboardCardDisplayMode.VALUE,
        )
        assertEquals(SleepMetricCardId.SLEEP_EFFICIENCY, config.cardId)
        assertEquals(false, config.isVisible)
        assertEquals(2, config.position)
        assertEquals(DashboardCardDisplayMode.VALUE, config.requestedDisplayMode)

        val json = Json.encodeToString(config)
        val decoded = Json.decodeFromString<SleepMetricCardConfiguration>(json)
        assertEquals(config, decoded)
    }
}
