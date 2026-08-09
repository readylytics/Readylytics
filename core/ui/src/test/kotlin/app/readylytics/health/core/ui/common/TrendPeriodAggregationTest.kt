package app.readylytics.health.core.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
    fun `quarterly bucket averages partial trailing bucket`() {
        val points =
            listOf(
                DailyDataPoint(0, 10f), // Q1
                DailyDataPoint(89, 20f), // Q1 last day
                DailyDataPoint(90, 30f), // Q2 first day
                DailyDataPoint(99, 50f), // Q2 partial
            )

        assertEquals(
            listOf(
                DailyDataPoint(44, 15f), // Q1 midpoint offset
                DailyDataPoint(135, 40f), // Q2 midpoint offset
            ),
            points.bucketBy(TrendGranularity.QUARTERLY, LocalDate.of(2026, 1, 1)),
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

        assertEquals("Feb", summary?.periodLabel)
        assertEquals("Jan", summary?.previousPeriodLabel)
        assertEquals(24f, summary?.average)
        assertEquals(20f, summary?.previousAverage)
    }

    @Test
    fun `quarterly summary uses quarter labels`() {
        val summary =
            buildPeriodAverageSummary(
                points = listOf(DailyDataPoint(44, 10f), DailyDataPoint(135, 20f)),
                granularity = TrendGranularity.QUARTERLY,
                startDate = LocalDate.of(2026, 1, 1),
            )

        assertEquals("Q2", summary?.periodLabel)
        assertEquals("Q1", summary?.previousPeriodLabel)
        assertEquals(20f, summary?.average)
        assertEquals(10f, summary?.previousAverage)
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
    fun `monthly labels use localized month abbreviations`() {
        assertEquals("Jan", periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2026, 1, 1)))
        assertEquals("Feb", periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2026, 2, 15)))
        assertEquals("Dec", periodLabelFor(TrendGranularity.MONTHLY, LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `quarterly labels use quarter numbers`() {
        assertEquals("Q1", periodLabelFor(TrendGranularity.QUARTERLY, LocalDate.of(2026, 1, 1)))
        assertEquals("Q1", periodLabelFor(TrendGranularity.QUARTERLY, LocalDate.of(2026, 3, 31)))
        assertEquals("Q2", periodLabelFor(TrendGranularity.QUARTERLY, LocalDate.of(2026, 4, 1)))
        assertEquals("Q3", periodLabelFor(TrendGranularity.QUARTERLY, LocalDate.of(2026, 7, 1)))
        assertEquals("Q4", periodLabelFor(TrendGranularity.QUARTERLY, LocalDate.of(2026, 10, 1)))
    }
}
