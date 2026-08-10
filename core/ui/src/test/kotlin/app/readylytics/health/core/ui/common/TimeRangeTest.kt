package app.readylytics.health.core.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimeRangeTest {
    @Test
    fun `SEVEN_DAYS fromMs should return timestamp for 6 days ago to include today as 7th day`() {
        val baseDate = LocalDate.of(2024, 5, 2)
        val zoneId = ZoneId.systemDefault()
        val expectedStart =
            baseDate
                .minusDays(6)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()

        // Current implementation:
        // return baseDate.atStartOfDay(zoneId).minusDays(days.toLong()).toInstant().toEpochMilli()
        // For 7 days, it returns baseDate - 7 days.

        val actualStart = TimeRange.SEVEN_DAYS.fromMs(baseDate)

        // If actualStart is baseDate - 7 days, it's 8 days total (0 to 7).
        val diffDays = (baseDate.atStartOfDay(zoneId).toInstant().toEpochMilli() - actualStart) / (24 * 60 * 60 * 1000L)

        println("Diff days for 7D: $diffDays")
        // We expect 6 days diff so that we have offsets 0, 1, 2, 3, 4, 5, 6 (7 days total)
        assertEquals(6L, diffDays)
    }

    @Test
    fun `TWELVE_MONTHS is a 360 day range labeled 360D`() {
        assertEquals(360, TimeRange.TWELVE_MONTHS.days)
        assertEquals("360D", TimeRange.TWELVE_MONTHS.label)
    }

    @Test
    fun `short ranges map to daily granularity`() {
        assertEquals(TrendGranularity.DAILY, TimeRange.SEVEN_DAYS.granularity)
        assertEquals(TrendGranularity.DAILY, TimeRange.THIRTY_DAYS.granularity)
    }

    @Test
    fun `six months maps to monthly and twelve months maps to quarterly granularity`() {
        assertEquals(TrendGranularity.MONTHLY, TimeRange.SIX_MONTHS.granularity)
        assertEquals(TrendGranularity.QUARTERLY, TimeRange.TWELVE_MONTHS.granularity)
    }
}
