package app.readylytics.health.core.ui.components

import app.readylytics.health.core.ui.common.DailyDataPoint
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
