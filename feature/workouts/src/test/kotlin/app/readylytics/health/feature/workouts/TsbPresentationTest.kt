package app.readylytics.health.feature.workouts

import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.TrendGranularity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TsbPresentationTest {
    @Test
    fun formatsTsbValueWithSign() {
        val positive = 14.2f
        val negative = -8.5f
        assertEquals("+14", String.format(java.util.Locale.US, "%+d", positive.toInt()))
        assertEquals("-8", String.format(java.util.Locale.US, "%+d", negative.toInt()))
    }

    @Test
    fun resolvesCorrectZoneStringResourceAcrossBoundaries() {
        assertEquals(R.string.tsb_zone_very_fresh, tsbZoneLabelRes(25.1f))
        assertEquals(R.string.tsb_zone_very_fresh, tsbZoneLabelRes(50f))

        assertEquals(R.string.tsb_zone_fresh, tsbZoneLabelRes(25.0f))
        assertEquals(R.string.tsb_zone_fresh, tsbZoneLabelRes(5.0f))

        assertEquals(R.string.tsb_zone_optimal, tsbZoneLabelRes(4.9f))
        assertEquals(R.string.tsb_zone_optimal, tsbZoneLabelRes(-10.0f))

        assertEquals(R.string.tsb_zone_fatigued, tsbZoneLabelRes(-10.1f))
        assertEquals(R.string.tsb_zone_fatigued, tsbZoneLabelRes(-30.0f))

        assertEquals(R.string.tsb_zone_overreached, tsbZoneLabelRes(-30.1f))
        assertEquals(R.string.tsb_zone_overreached, tsbZoneLabelRes(-55f))
    }

    @Test
    fun buildsDailyTooltipDataCorrectly() {
        val startMs =
            LocalDate
                .of(2026, 9, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val expectedDate = ChartUtils.formatTooltipDate(LocalDate.of(2026, 9, 4))
        val params =
            TsbTooltipParams(
                tsbValue = 12.3f,
                dayOffset = 3,
                rangeStartMs = startMs,
                granularity = TrendGranularity.DAILY,
                periodLabels = emptyList(),
                canvasX = 150f,
                canvasY = 80f,
                tsbFormat = "TSB: %1\$s",
                avgTsbFormat = "Avg. TSB: %1\$s",
                zoneText = "Optimal / Productive",
            )
        val tooltip = params.toTooltipData()

        assertEquals("TSB: +12", tooltip.valueText)
        assertEquals("Optimal / Productive", tooltip.dateText)
        assertEquals(expectedDate, tooltip.extraLine)
        assertEquals(IntOffset(150, 80), tooltip.offset)
    }

    @Test
    fun buildsNonDailyTooltipDataCorrectly() {
        val params =
            TsbTooltipParams(
                tsbValue = -14.6f,
                dayOffset = 2,
                rangeStartMs = 0L,
                granularity = TrendGranularity.MONTHLY,
                periodLabels = listOf("W34", "W35", "W36"),
                canvasX = 220f,
                canvasY = 110f,
                tsbFormat = "TSB: %1\$s",
                avgTsbFormat = "Avg. TSB: %1\$s",
                zoneText = "Fatigued / Overload",
            )
        val tooltip = params.toTooltipData()

        assertEquals("W36", tooltip.valueText)
        assertEquals("Avg. TSB: -15", tooltip.dateText)
        assertEquals("Fatigued / Overload", tooltip.extraLine)
        assertEquals(IntOffset(220, 110), tooltip.offset)
    }
}
