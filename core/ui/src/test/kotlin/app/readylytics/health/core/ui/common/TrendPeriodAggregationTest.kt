package app.readylytics.health.core.ui.common

import app.readylytics.health.core.ui.components.formatTrendTooltipDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TrendPeriodAggregationTest {
    @Test
    fun `monthly bucket averages values and uses bucket midpoint`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f), // 2026-01-01
                DailyDataPoint(14, 20f), // 2026-01-15
                DailyDataPoint(30, 30f), // 2026-01-31
                DailyDataPoint(31, 40f), // 2026-02-01
            )

        assertEquals(
            listOf(
                DailyDataPoint(15, 20f), // January midpoint offset
                DailyDataPoint(44, 40f), // February midpoint offset
            ),
            points.bucketBy(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `bucket midpoint is clamped to selected range`() {
        val startDate = LocalDate.of(2026, 1, 20)
        val points =
            listOf(
                DailyDataPoint(0, 10f), // January 20
                DailyDataPoint(21, 20f), // February 10
            )

        assertEquals(
            listOf(
                DailyDataPoint(0, 10f),
                DailyDataPoint(21, 20f),
            ),
            points.bucketBy(
                granularity = TrendGranularity.MONTHLY,
                startDate = startDate,
                endDate = LocalDate.of(2026, 2, 10),
            ),
        )
    }

    @Test
    fun `eight week bucket averages partial trailing bucket`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f), // Octad 1 (weeks 1-8)
                DailyDataPoint(30, 20f), // Octad 1 last day
                DailyDataPoint(55, 30f), // Octad 2 (weeks 9-16) first day
                DailyDataPoint(79, 50f), // Octad 2 partial
            )

        assertEquals(
            listOf(
                DailyDataPoint(24, 15f), // Octad 1 midpoint offset (Jan 25)
                DailyDataPoint(80, 40f), // Octad 2 midpoint offset (Mar 22)
            ),
            points.bucketBy(TrendGranularity.EIGHT_WEEK, LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `monthly bucket rounds fractional averages to integer by default`() {
        val points =
            listOf(
                DailyDataPoint(0, 94.4f), // January
                DailyDataPoint(1, 94.6f), // January
                DailyDataPoint(31, 96.3f), // February
            )

        assertEquals(
            listOf(
                DailyDataPoint(15, 95f), // January avg 94.5 rounded
                DailyDataPoint(44, 96f), // February avg 96.3 rounded
            ),
            points.bucketBy(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `bucket average respects configured decimal places`() {
        val points =
            listOf(
                DailyDataPoint(0, 36.5f), // January
                DailyDataPoint(1, 36.8f), // January
            )

        assertEquals(
            listOf(DailyDataPoint(15, 36.7f)), // avg 36.65 rounded to 1 decimal
            points.bucketBy(
                granularity = TrendGranularity.MONTHLY,
                startDate = LocalDate.of(2026, 1, 1),
                valueDecimalPlaces = 1,
            ),
        )
    }

    @Test
    fun `missing days do not create empty buckets or affect average`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f), // January
                DailyDataPoint(2, 30f), // January
                DailyDataPoint(59, 50f), // March (February has no data)
            )

        assertEquals(
            listOf(
                DailyDataPoint(15, 20f), // January midpoint offset
                DailyDataPoint(74, 50f), // March midpoint offset
            ),
            points.bucketBy(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `null values are ignored inside a bucket`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f),
                DailyDataPoint(1, null),
                DailyDataPoint(2, 30f),
            )

        assertEquals(
            listOf(DailyDataPoint(15, 20f)),
            points.bucketBy(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `single-day month and single bucket remain valid`() {
        val points = listOf(DailyDataPoint(31, 42f)) // 2026-02-01

        assertEquals(
            listOf(DailyDataPoint(44, 42f)),
            points.bucketBy(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)),
        )
        assertNull(
            buildPeriodAverageSummary(
                points = listOf(DailyDataPoint(44, 42f)),
                granularity = TrendGranularity.MONTHLY,
                startDate = LocalDate.of(2026, 1, 1),
            ),
        )
    }

    @Test
    fun `empty list buckets to empty and has no summary`() {
        assertEquals(
            emptyList<DailyDataPoint>(),
            emptyList<DailyDataPoint>().bucketBy(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)),
        )
        assertNull(
            buildPeriodAverageSummary(
                points = emptyList(),
                granularity = TrendGranularity.MONTHLY,
                startDate = LocalDate.of(2026, 1, 1),
            ),
        )
    }

    @Test
    fun `summary compares latest two populated buckets`() {
        val summary =
            buildPeriodAverageSummary(
                points = listOf(DailyDataPoint(15, 20f), DailyDataPoint(44, 24f)),
                granularity = TrendGranularity.MONTHLY,
                startDate = LocalDate.of(2026, 1, 1),
            )

        assertEquals(TrendGranularity.MONTHLY, summary?.granularity)
        assertEquals(LocalDate.of(2026, 2, 14), summary?.periodStartDate)
        assertEquals(LocalDate.of(2026, 1, 16), summary?.previousPeriodStartDate)
        assertEquals(24f, summary?.average)
        assertEquals(20f, summary?.previousAverage)
    }

    @Test
    fun `eight week summary uses bucket midpoint dates`() {
        val summary =
            buildPeriodAverageSummary(
                points = listOf(DailyDataPoint(24, 10f), DailyDataPoint(80, 20f)),
                granularity = TrendGranularity.EIGHT_WEEK,
                startDate = LocalDate.of(2026, 1, 1),
            )

        assertEquals(LocalDate.of(2026, 1, 1).plusDays(80), summary?.periodStartDate)
        assertEquals(LocalDate.of(2026, 1, 1).plusDays(24), summary?.previousPeriodStartDate)
    }

    @Test
    fun `summary requires at least two populated buckets`() {
        assertNull(
            buildPeriodAverageSummary(
                points = listOf(DailyDataPoint(15, 20f)),
                granularity = TrendGranularity.MONTHLY,
                startDate = LocalDate.of(2026, 1, 1),
            ),
        )
    }

    @Test
    fun `daily granularity returns original non null points in order`() {
        val points =
            listOf(
                DailyDataPoint(3, 5f),
                DailyDataPoint(1, null),
                DailyDataPoint(2, 7f),
            )

        assertEquals(
            listOf(DailyDataPoint(2, 7f), DailyDataPoint(3, 5f)),
            points.bucketBy(TrendGranularity.DAILY, LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `eight week bucket start anchors to ISO week octad Monday`() {
        val date = LocalDate.of(2026, 2, 25) // mid-week
        val start = bucketStartForDate(date, TrendGranularity.EIGHT_WEEK)
        assertEquals(LocalDate.of(2026, 2, 23), start) // Week 9 Monday of 2026
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
    }

    @Test
    fun `eight week full octad is 56 days`() {
        val start = LocalDate.of(2026, 2, 23) // Week 9 Monday
        assertEquals(56, bucketLengthDays(start, TrendGranularity.EIGHT_WEEK))
    }

    @Test
    fun `eight week trailing bucket is shorter for non-divisible years`() {
        // 2026 has 53 ISO weeks. Week 49 octad (last) = weeks 49-53 = 5 weeks.
        // Week 49 Monday 2026 = Nov 30.
        val start = LocalDate.of(2026, 11, 30)
        val startAdjusted = bucketStartForDate(start, TrendGranularity.EIGHT_WEEK)
        val length = bucketLengthDays(startAdjusted, TrendGranularity.EIGHT_WEEK)
        assertTrue("Trailing bucket should be < 56 days, got $length", length < 56)
        assertEquals(35, length)
    }

    @Test
    fun `periodLabelFor EIGHT_WEEK shows ISO week number`() {
        val date = LocalDate.of(2026, 2, 25)
        val label = periodLabelFor(TrendGranularity.EIGHT_WEEK, date) { "Wk $it" }
        assertEquals("Wk 9", label)
    }

    @Test
    fun `periodLabelFor EIGHT_WEEK shows the date week for mid octad dates`() {
        val date = LocalDate.of(2026, 3, 10) // week 11, mid octad (weeks 9-16)
        val label = periodLabelFor(TrendGranularity.EIGHT_WEEK, date) { "Wk $it" }
        assertEquals("Wk 11", label)
    }

    @Test
    fun `formatTrendTooltipDate EIGHT_WEEK shows week range`() {
        val date = LocalDate.of(2026, 2, 25)
        val result =
            formatTrendTooltipDate(
                TrendGranularity.EIGHT_WEEK,
                date,
                { "Wk $it" },
                "Weeks %1\$d–%2\$d",
            )
        assertEquals("Weeks 9–16", result)
    }

    @Test
    fun `formatTrendTooltipDate EIGHT_WEEK shows week range anchored to octad for mid octad dates`() {
        val date = LocalDate.of(2026, 3, 10) // week 11, mid octad (weeks 9-16)
        val result =
            formatTrendTooltipDate(
                TrendGranularity.EIGHT_WEEK,
                date,
                { "Wk $it" },
                "Weeks %1\$d–%2\$d",
            )
        assertEquals("Weeks 9–16", result)
    }

    @Test
    fun `monthly labels use localized month abbreviations`() {
        assertEquals("Jan", periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)) { "Q$it" })
        assertEquals("Feb", periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2026, 2, 15)) { "Q$it" })
        assertEquals("Dec", periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2026, 12, 31)) { "Q$it" })
    }

    @Test
    fun `allBucketOffsets returns all period midpoints for monthly`() {
        val offsets =
            allBucketOffsets(
                TrendGranularity.MONTHLY,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
            )
        assertEquals(listOf(15, 44, 74, 104, 135, 165), offsets)
    }

    @Test
    fun `allBucketOffsets returns all period midpoints for eight week`() {
        val offsets =
            allBucketOffsets(
                TrendGranularity.EIGHT_WEEK,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
            )
        assertEquals(listOf(24, 80, 136, 180), offsets)
    }

    @Test
    fun `allBucketOffsets returns empty for daily`() {
        val offsets =
            allBucketOffsets(
                TrendGranularity.DAILY,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 7),
            )
        assertEquals(emptyList<Int>(), offsets)
    }

    @Test
    fun `padBucketsToRange fills all period positions`() {
        val points =
            listOf(
                DailyDataPoint(15, 20f), // January
                DailyDataPoint(104, 40f), // April -- February and March missing
            )
        val padded =
            points.padBucketsToRange(
                TrendGranularity.MONTHLY,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30),
            )
        assertEquals(
            listOf(
                DailyDataPoint(15, 20f),
                DailyDataPoint(44, null),
                DailyDataPoint(74, null),
                DailyDataPoint(104, 40f),
                DailyDataPoint(135, null),
                DailyDataPoint(165, null),
            ),
            padded,
        )
    }

    @Test
    fun `padBucketsToRange returns original list for daily`() {
        val points = listOf(DailyDataPoint(0, 5f))
        assertEquals(
            points,
            points.padBucketsToRange(TrendGranularity.DAILY, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `fixed day bucket of size one yields one unaveraged bucket per populated day`() {
        val points =
            listOf(
                DailyDataPoint(0, 60f),
                DailyDataPoint(2, 58f),
                DailyDataPoint(5, 62f),
            )

        assertEquals(
            listOf(
                FixedDayBucket(0, 1, 0, 60f),
                FixedDayBucket(2, 3, 2, 58f),
                FixedDayBucket(5, 6, 5, 62f),
            ),
            points.bucketByFixedSize(bucketSizeDays = 1, rangeEndOffsetExclusive = 7),
        )
    }

    @Test
    fun `fixed day bucket of size two pairs consecutive days and averages each pair`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f),
                DailyDataPoint(1, 20f),
                DailyDataPoint(2, 30f),
                DailyDataPoint(3, 50f),
                DailyDataPoint(4, 10f),
            )

        assertEquals(
            listOf(
                FixedDayBucket(0, 2, 1, 15f),
                FixedDayBucket(2, 4, 3, 40f),
                FixedDayBucket(4, 6, 5, 10f),
            ),
            points.bucketByFixedSize(bucketSizeDays = 2, rangeEndOffsetExclusive = 30),
        )
    }

    @Test
    fun `fixed day bucket omits bucket with no non null values`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f),
                DailyDataPoint(1, 20f),
                DailyDataPoint(2, null),
                DailyDataPoint(3, null),
                DailyDataPoint(4, 50f),
                DailyDataPoint(5, 60f),
            )

        assertEquals(
            listOf(
                FixedDayBucket(0, 2, 1, 15f),
                FixedDayBucket(4, 6, 5, 55f),
            ),
            points.bucketByFixedSize(bucketSizeDays = 2, rangeEndOffsetExclusive = 6),
        )
    }

    @Test
    fun `fixed day bucket with one non null day averages to that lone value`() {
        val points = listOf(DailyDataPoint(3, 70f))

        assertEquals(
            listOf(FixedDayBucket(2, 4, 3, 70f)),
            points.bucketByFixedSize(bucketSizeDays = 2, rangeEndOffsetExclusive = 10),
        )
    }

    @Test
    fun `fixed day bucket clips final bucket to range end`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f),
                DailyDataPoint(1, 20f),
                DailyDataPoint(4, 50f),
            )

        assertEquals(
            listOf(
                FixedDayBucket(0, 2, 1, 15f),
                FixedDayBucket(4, 5, 4, 50f),
            ),
            points.bucketByFixedSize(bucketSizeDays = 2, rangeEndOffsetExclusive = 5),
        )
    }

    @Test
    fun `fixed day bucket respects configured decimal places`() {
        val points =
            listOf(
                DailyDataPoint(0, 36.5f),
                DailyDataPoint(1, 36.8f),
            )

        assertEquals(
            listOf(FixedDayBucket(0, 2, 1, 36.7f)),
            points.bucketByFixedSize(
                bucketSizeDays = 2,
                rangeEndOffsetExclusive = 2,
                valueDecimalPlaces = 1,
            ),
        )
    }
}
