package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Offset
import app.readylytics.health.core.ui.common.DailyDataPoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendChartRenderDataTest {
    @Test
    fun `empty input produces empty render data`() {
        val result = buildTrendChartRenderData(emptyList())

        assertEquals(emptyList(), result.validPoints)
        assertEquals(emptyMap(), result.pointByDayOffset)
        assertNull(result.calculatedBaseline)
        assertNull(result.minimum)
        assertNull(result.maximum)
    }

    @Test
    fun `null values are excluded and valid input order is preserved`() {
        val result =
            buildTrendChartRenderData(
                listOf(
                    DailyDataPoint(2, 30f),
                    DailyDataPoint(0, null),
                    DailyDataPoint(1, 10f),
                ),
            )

        assertEquals(listOf(DailyDataPoint(2, 30f), DailyDataPoint(1, 10f)), result.validPoints)
        assertEquals(DailyDataPoint(1, 10f), result.pointByDayOffset[1])
        assertNull(result.pointByDayOffset[0])
        assertEquals(20f, result.calculatedBaseline)
        assertEquals(10f, result.minimum)
        assertEquals(30f, result.maximum)
    }

    @Test
    fun `odd value count uses middle sorted value`() {
        val result =
            buildTrendChartRenderData(
                listOf(DailyDataPoint(0, 40f), DailyDataPoint(1, 10f), DailyDataPoint(2, 20f)),
            )

        assertEquals(20f, result.calculatedBaseline)
    }

    @Test
    fun `tooltip formatting preserves chart output contract`() {
        assertEquals("—", formatTrendTooltipValue(null, 0, false, "ms"))
        assertEquals("42 ms", formatTrendTooltipValue(42.4f, 0, false, "ms"))
        assertEquals("42.4 %", formatTrendTooltipValue(42.44f, 1, false, "%"))
        assertEquals("42", formatTrendTooltipValue(42f, 0, true, "steps"))
    }

    @Test
    fun `marker work is suppressed during parent scroll`() {
        assertFalse(shouldProcessTrendMarker(parentScrollInProgress = true))
        assertTrue(shouldProcessTrendMarker(parentScrollInProgress = false))
    }

    @Test
    fun `equivalent marker state is not assigned`() {
        assertFalse(shouldAssignTrendMarkerState(current = Offset(4f, 8f), next = Offset(4f, 8f)))
        assertTrue(shouldAssignTrendMarkerState(current = Offset(4f, 8f), next = Offset(5f, 8f)))
    }
}
