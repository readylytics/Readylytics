package app.readylytics.health.core.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DayOffsetTickCalculatorTest {
    /**
     * Golden oracle: a verbatim copy of the pre-caching `calculateValues` body from
     * `ChartDefaults.itemPlacerForRangeDays` (parameterized by `rangeDays`), including the trailing
     * `.sorted()` that the cached implementation no longer performs. Kept as-is so the cached
     * implementation can be proven byte-identical to the code it replaces, for every input.
     */
    private fun referenceValues(
        rangeDays: Int,
        visibleXRange: ClosedFloatingPointRange<Double>,
    ): List<Double> {
        val visibleDays = visibleXRange.endInclusive - visibleXRange.start

        // If mostly/fully zoomed out, use perfectly spaced 6-label lists to avoid strange jumps
        if (visibleDays > rangeDays - 2.0) {
            val zoomedOutList =
                when (rangeDays) {
                    30 -> listOf(0.0, 6.0, 12.0, 18.0, 24.0, 29.0)
                    180 -> listOf(0.0, 36.0, 72.0, 108.0, 144.0, 179.0)
                    else -> null
                }
            if (zoomedOutList != null) {
                val buffer = 0.01
                return zoomedOutList.filter {
                    it in (visibleXRange.start - buffer)..(visibleXRange.endInclusive + buffer)
                }
            }
        }

        val spacing = referenceSpacing(visibleDays)
        val maxVal = (rangeDays - 1).toDouble()
        val values = mutableListOf<Double>()
        var current = 0.0
        while (current <= maxVal) {
            values.add(current)
            current += spacing.toDouble()
        }

        val buffer = 0.01
        val visibleValues =
            values
                .filter {
                    it in (visibleXRange.start - buffer)..(visibleXRange.endInclusive + buffer)
                }.toMutableList()

        val firstDay = 0.0
        if (firstDay in visibleXRange && !visibleValues.contains(firstDay)) {
            visibleValues.add(0, firstDay)
        }

        adjustReferenceMaxVal(visibleXRange, maxVal, visibleDays, visibleValues)

        return visibleValues.sorted()
    }

    private fun referenceSpacing(visibleDays: Double): Int =
        when {
            visibleDays <= 1.1 -> 1
            visibleDays <= 3.5 -> 2
            visibleDays <= 8.5 -> 2
            visibleDays <= 15.5 -> 2
            visibleDays <= 35.0 -> 5
            visibleDays <= 70.0 -> 10
            visibleDays <= 120.0 -> 15
            else -> 35
        }

    private fun referenceMinSeparation(visibleDays: Double): Double =
        when {
            visibleDays <= 1.1 -> 0.1
            visibleDays <= 3.5 -> 0.1
            visibleDays <= 8.5 -> if (visibleDays <= 5.5) 0.5 else 1.1
            visibleDays <= 15.5 -> 1.1
            visibleDays <= 35.0 -> 4.0
            visibleDays <= 70.0 -> 8.0
            visibleDays <= 120.0 -> 12.0
            else -> 24.0
        }

    private fun adjustReferenceMaxVal(
        visibleXRange: ClosedFloatingPointRange<Double>,
        maxVal: Double,
        visibleDays: Double,
        visibleValues: MutableList<Double>,
    ) {
        if (maxVal in visibleXRange && !visibleValues.contains(maxVal)) {
            val minSeparation = referenceMinSeparation(visibleDays)
            val lastValue = visibleValues.lastOrNull() ?: 0.0
            if (maxVal - lastValue < minSeparation) {
                visibleValues.removeAt(visibleValues.size - 1)
            }
            visibleValues.add(maxVal)
        }
    }

    /**
     * Cross-product fixture: for a given rangeDays, a grid of visible ranges covering the full
     * domain, the zoomed-out branch boundary (rangeDays - 2.0) exactly / just under / just over,
     * fractional windows at the start/middle/end of the domain, a sub-day window, windows
     * straddling 0.0 and maxVal, windows offset by exactly the 0.01 filter buffer on each edge, and
     * a window extending past maxVal.
     */
    private fun rangesFor(rangeDays: Int): List<ClosedFloatingPointRange<Double>> {
        val maxVal = (rangeDays - 1).toDouble()
        val boundary = rangeDays - 2.0
        return listOf(
            0.0..maxVal,
            0.0..boundary,
            0.0..(boundary - 0.001),
            0.0..(boundary + 0.001),
            -0.01..maxVal,
            0.0..(maxVal + 0.01),
            -0.02..maxVal,
            0.0..(maxVal + 0.02),
            0.0..0.5,
            0.0..1.0,
            0.0..2.0,
            0.0..3.0,
            0.0..5.0,
            0.0..8.0,
            0.0..15.0,
            0.0..30.0,
            0.0..35.0,
            0.0..70.0,
            0.0..120.0,
            0.5..1.5,
            0.5..3.0,
            1.0..5.0,
            2.5..7.5,
            5.0..15.0,
            10.0..25.0,
            (maxVal - 1.0)..maxVal,
            (maxVal - 2.0)..maxVal,
            (maxVal - 5.0)..maxVal,
            (maxVal - 10.0)..maxVal,
            (maxVal - 30.0)..maxVal,
            (maxVal / 3)..(2 * maxVal / 3),
            ((maxVal / 2) - 0.5)..((maxVal / 2) + 0.5),
            -5.0..(maxVal + 5.0),
        )
    }

    @Test
    fun `outputs match golden reference across standard ranges and representative viewports`() {
        val testRanges = listOf(7, 14, 30, 60, 90, 180, 360)
        for (rangeDays in testRanges) {
            val calc = DayOffsetTickCalculator(rangeDays)
            for (visibleRange in rangesFor(rangeDays)) {
                val expected = referenceValues(rangeDays, visibleRange)
                val actual = calc.values(visibleRange)
                assertEquals(
                    expected,
                    actual,
                    "Mismatch for rangeDays=$rangeDays, visibleRange=$visibleRange",
                )
            }
        }
    }

    @Test
    fun `repeated calls with identical range return identical instance`() {
        val calc = DayOffsetTickCalculator(30)
        val range = 0.0..29.0

        val first = calc.values(range)
        val second = calc.values(range)

        assertSame(
            first,
            second,
            "Calculator must return cached List instance on repeated calls with identical range",
        )
    }

    @Test
    fun `different range recomputes and updates cache`() {
        val calc = DayOffsetTickCalculator(30)
        val range1 = 0.0..29.0
        val range2 = 0.0..15.0

        val res1 = calc.values(range1)
        val res2 = calc.values(range2)
        val res3 = calc.values(range2)

        assertEquals(referenceValues(30, range1), res1)
        assertEquals(referenceValues(30, range2), res2)
        assertSame(res2, res3, "Subsequent query for range2 should hit cache")
    }

    @Test
    fun `all produced tick lists are strictly ascending`() {
        val testRanges = listOf(7, 14, 30, 60, 90, 180, 360)
        for (rangeDays in testRanges) {
            val calc = DayOffsetTickCalculator(rangeDays)
            for (visibleRange in rangesFor(rangeDays)) {
                val values = calc.values(visibleRange)
                for (i in 0 until values.size - 1) {
                    assertTrue(
                        values[i] < values[i + 1],
                        "List not strictly ascending for rangeDays=$rangeDays, " +
                            "visibleRange=$visibleRange: ${values[i]} vs ${values[i + 1]} in $values",
                    )
                }
            }
        }
    }

    @Test
    fun `rangeDays 360 keeps strictly ascending ticks including both endpoints`() {
        val calculator = DayOffsetTickCalculator(360)
        val range = 0.0..359.0
        val values = calculator.values(range)
        assertTrue(values.first() == 0.0)
        assertTrue(values.last() == 359.0)
        assertEquals(values.sorted(), values)
        assertEquals(values.distinct(), values)
        assertTrue(values.zipWithNext().all { (a, b) -> b - a > 0.0 })
    }

    @Test
    fun `cache hit returns the identical instance`() {
        val calculator = DayOffsetTickCalculator(30)
        val range = 0.0..10.0

        val first = calculator.values(range)
        val second = calculator.values(range)

        assertSame(first, second)
    }

    @Test
    fun `cache hit returns the identical instance for the zoomed-out early return`() {
        // rangeDays = 30, visibleDays = 29 > (rangeDays - 2.0) = 28.0, so this hits the
        // zoomedOutValues early return rather than the general spacing path above.
        val calculator = DayOffsetTickCalculator(30)
        val range = 0.0..29.0

        val first = calculator.values(range)
        val second = calculator.values(range)

        assertSame(first, second)
    }

    @Test
    fun `cache miss on a changed range recomputes and matches reference`() {
        val calculator = DayOffsetTickCalculator(30)
        val rangeA = 0.0..10.0
        val rangeB = 0.0..20.0

        val resultA = calculator.values(rangeA)
        val resultB = calculator.values(rangeB)

        assertEquals(referenceValues(30, rangeA), resultA)
        assertEquals(referenceValues(30, rangeB), resultB)
    }

    @Test
    fun `alternating ranges still return correct values for the earlier range`() {
        val calculator = DayOffsetTickCalculator(90)
        val rangeA = 0.0..10.0
        val rangeB = 20.0..40.0

        val firstA = calculator.values(rangeA)
        calculator.values(rangeB)
        val secondA = calculator.values(rangeA)
        calculator.values(rangeB)
        val thirdA = calculator.values(rangeA)

        assertEquals(referenceValues(90, rangeA), firstA)
        assertEquals(referenceValues(90, rangeA), secondA)
        assertEquals(referenceValues(90, rangeA), thirdA)
    }

    @Test
    fun `tickValuesFor places one tick per bucketed point offset`() {
        assertEquals(
            listOf(45.0, 135.0, 225.0, 315.0),
            ChartDefaults.tickValuesFor(360, listOf(315, 45, 225, 135), 0.0..359.0),
        )
    }

    @Test
    fun `tickValuesFor without point offsets delegates to the daily calculator`() {
        assertEquals(
            listOf(0.0, 6.0, 12.0, 18.0, 24.0, 29.0),
            ChartDefaults.tickValuesFor(30, emptyList(), 0.0..29.0),
        )
    }

    @Test
    fun `spacing-candidate reuse produces identical output across calls in the same bucket`() {
        // rangeDays = 180, zoomed-out branch requires visibleDays > 178.0; both ranges below stay
        // well under that, so both hit the general path with spacing = 5 (visibleDays <= 35.0).
        val calculator = DayOffsetTickCalculator(180)
        val rangeOne = 0.0..30.0
        val rangeTwo = 50.0..80.0

        val firstOne = calculator.values(rangeOne)
        val firstTwo = calculator.values(rangeTwo)
        val secondOne = calculator.values(rangeOne)
        val secondTwo = calculator.values(rangeTwo)

        assertEquals(referenceValues(180, rangeOne), firstOne)
        assertEquals(referenceValues(180, rangeTwo), firstTwo)
        assertEquals(referenceValues(180, rangeOne), secondOne)
        assertEquals(referenceValues(180, rangeTwo), secondTwo)
    }

    @Test
    fun `subsampleTicks keeps list unchanged when within cap`() {
        val ticks = listOf(20.0, 76.0, 118.0)
        assertEquals(ticks, ChartDefaults.subsampleTicks(ticks, maxTicks = 5))
    }

    @Test
    fun `subsampleTicks caps to maxTicks preserving first and last`() {
        // 360D EIGHT_WEEK bucket midpoints observed on device.
        val ticks = listOf(20.0, 76.0, 118.0, 160.0, 216.0, 272.0, 328.0, 359.0)
        val result = ChartDefaults.subsampleTicks(ticks, maxTicks = ChartDefaults.MAX_X_AXIS_TICKS)
        assertEquals(ChartDefaults.MAX_X_AXIS_TICKS, result.size)
        assertEquals(20.0, result.first())
        assertEquals(359.0, result.last())
        assertEquals(result.toSortedSet().toList(), result)
    }

    @Test
    fun `subsampleTicks spreads evenly across dense ticks`() {
        val ticks = (0..359).map { it.toDouble() }
        val result = ChartDefaults.subsampleTicks(ticks, maxTicks = 4)
        assertEquals(listOf(0.0, 120.0, 239.0, 359.0), result)
    }
}
