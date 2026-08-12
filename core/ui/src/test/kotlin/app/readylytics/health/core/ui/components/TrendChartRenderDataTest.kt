package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Offset
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import org.junit.Test
import java.time.LocalDate
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
    fun `period value and tooltip value share one formatter`() {
        // hideUnit=false appends the unit — exactly what PeriodAverageSummaryRow needs.
        assertEquals("24.5 kg", formatTrendTooltipValue(24.5f, 1, false, "kg"))
        assertEquals("25 kg", formatTrendTooltipValue(24.5f, 0, false, "kg"))
    }

    @Test
    fun `tooltip date uses period label for monthly granularity`() {
        val date = LocalDate.of(2026, 7, 15)
        assertEquals("Jul", formatTrendTooltipDate(TrendGranularity.MONTHLY, date, { "Q$it" }))
    }

    @Test
    fun `tooltip date uses week range for eight week granularity`() {
        val date = LocalDate.of(2026, 7, 5)
        assertEquals(
            "Weeks 25–32",
            formatTrendTooltipDate(
                TrendGranularity.EIGHT_WEEK,
                date,
                { week -> "Wk$week" },
                "Weeks %1\$d–%2\$d",
            ),
        )
    }

    @Test
    fun `tooltip date keeps short date format for daily granularity`() {
        val date = LocalDate.of(2026, 7, 15)
        assertEquals(
            ChartUtils.formatTooltipDate(date),
            formatTrendTooltipDate(TrendGranularity.DAILY, date, { "Q$it" }),
        )
    }

    @Test
    fun `baseline legend identifies calibration when a baseline is unavailable`() {
        assertEquals(
            "Baseline: Calibrating",
            formatBaselineLegendText(
                value = null,
                unit = "°C",
                label = "Baseline",
                decimalPlaces = 1,
                unavailableValueLabel = "Calibrating",
            ),
        )
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
