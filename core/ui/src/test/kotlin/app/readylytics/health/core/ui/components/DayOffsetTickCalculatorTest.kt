package app.readylytics.health.core.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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

        val spacing =
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

        if (maxVal in visibleXRange && !visibleValues.contains(maxVal)) {
            val minSeparation =
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
            val lastValue = visibleValues.lastOrNull() ?: 0.0
            if (maxVal - lastValue < minSeparation) {
                visibleValues.removeAt(visibleValues.size - 1)
            }
            visibleValues.add(maxVal)
        }

        return visibleValues.sorted()
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

        fun window(
            fraction: Double,
            position: String,
        ): ClosedFloatingPointRange<Double> {
            val width = maxVal * fraction
            return when (position) {
                "start" -> 0.0..width
                "end" -> (maxVal - width)..maxVal
                else -> {
                    val mid = maxVal / 2.0
                    (mid - width / 2.0)..(mid + width / 2.0)
                }
            }
        }

        return buildList {
            add(0.0..maxVal) // full domain
            add(0.0..boundary) // exactly the zoomed-out boundary (strictly-greater-than, so this must NOT trigger it)
            add(0.0..(boundary - 0.1)) // just under the boundary
            add(0.0..(boundary + 0.1)) // just over the boundary
            for (fraction in listOf(0.5, 0.25, 0.125)) {
                for (position in listOf("start", "middle", "end")) {
                    add(window(fraction, position))
                }
            }
            add(5.0..5.4) // sub-day window
            add(-0.5..2.0) // straddles 0.0
            add((maxVal - 2.0)..(maxVal + 1.0)) // straddles maxVal
            add(0.01..(maxVal - 0.01)) // offset by the 0.01 buffer on each edge, inward
            add(-0.01..(maxVal + 0.01)) // offset by the 0.01 buffer on each edge, outward
            add(0.0..(maxVal + 5.0)) // extends past maxVal
        }
    }

    @Test
    fun `golden vs reference across the full grid`() {
        for (rangeDays in listOf(7, 30, 90, 180)) {
            val calculator = DayOffsetTickCalculator(rangeDays)
            for (range in rangesFor(rangeDays)) {
                assertEquals(
                    referenceValues(rangeDays, range),
                    calculator.values(range),
                    "mismatch for rangeDays=$rangeDays range=$range",
                )
            }
        }
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
}
