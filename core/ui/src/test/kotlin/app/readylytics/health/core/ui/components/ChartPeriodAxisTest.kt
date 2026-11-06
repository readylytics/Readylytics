package app.readylytics.health.core.ui.components

import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

class ChartPeriodAxisTest {
    private val ordinal: (Int) -> String = { "W$it" }

    @Test
    fun `remap daily returns same list`() {
        val points = listOf(DailyDataPoint(3, 1f), DailyDataPoint(5, null))
        assertSame(points, remapToOrdinalOffsets(points, TrendGranularity.DAILY))
    }

    @Test
    fun `remap non-daily reindexes offsets and keeps values`() {
        val points = listOf(DailyDataPoint(3, 1f), DailyDataPoint(10, null), DailyDataPoint(17, 2f))
        val remapped = remapToOrdinalOffsets(points, TrendGranularity.MONTHLY)
        assertEquals(listOf(0, 1, 2), remapped.map { it.dayOffset })
        assertEquals(listOf(1f, null, 2f), remapped.map { it.value })
    }

    @Test
    fun `period labels daily is empty`() {
        val labels =
            periodLabelsFor(listOf(DailyDataPoint(0, 1f)), 0L, TrendGranularity.DAILY, ZoneId.of("UTC"), ordinal)
        assertTrue(labels.isEmpty())
    }

    @Test
    fun `period labels use supplied zone`() {
        val rangeStartMs = Instant.parse("2025-12-31T23:30:00Z").toEpochMilli()
        val zone = ZoneId.of("Pacific/Kiritimati") // UTC+14, so local date is 2026-01-01
        val labels =
            periodLabelsFor(listOf(DailyDataPoint(0, 1f)), rangeStartMs, TrendGranularity.MONTHLY, zone, ordinal)
        val expected = periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1), ordinal)
        val utcLabel = periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2025, 12, 31), ordinal)
        assertEquals(listOf(expected), labels)
        assertTrue("zone must change the label", expected != utcLabel)
    }

    @Test
    fun `period label formatter with empty labels falls back to empty string`() {
        val formatter = periodLabelFormatter(emptyList())
        assertEquals("", formatter.format(mockk<CartesianMeasuringContext>(relaxed = true), 3.0, null).toString())
    }

    @Test
    fun `period label formatter out of range falls back to first label`() {
        val formatter = periodLabelFormatter(listOf("A", "B"))
        val context = mockk<CartesianMeasuringContext>(relaxed = true)
        assertEquals("A", formatter.format(context, 5.0, null).toString())
        assertEquals("B", formatter.format(context, 1.0, null).toString())
    }

    @Test
    fun `fixed x range provider pins x and delegates y`() {
        val provider =
            fixedXRangeProvider(
                xAxisRangeDays = 30,
                minYOf = { _, _ -> 0.0 },
                maxYOf = { _, max -> ceil(max / 25.0) * 25.0 },
            )
        val extras = mockk<ExtraStore>()
        assertEquals(0.0, provider.getMinX(1.0, 2.0, extras), 0.0)
        assertEquals(29.0, provider.getMaxX(1.0, 2.0, extras), 0.0)
        assertEquals(0.0, provider.getMinY(5.0, 130.0, extras), 0.0)
        assertEquals(150.0, provider.getMaxY(0.0, 130.0, extras), 0.0)
    }
}
