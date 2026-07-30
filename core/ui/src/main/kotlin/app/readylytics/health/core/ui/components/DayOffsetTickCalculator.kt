package app.readylytics.health.core.ui.components

/**
 * Memoizes x-axis tick day-offsets for a chart whose x-domain is `0..rangeDays-1`.
 *
 * Safe to cache without bounds: candidates are generated once per `rangeDays`/`spacing` pair
 * (<= ~180 entries), Vico measures and draws on a single thread, and the calculator instance
 * lives and dies with the caller's remember(rangeDays) scope, so a range change discards it.
 * The single-entry result cache further collapses the three Vico calls that ask for the same
 * visible range within one frame (getLabelValues/getLineValues in drawOverLayers, plus
 * getLineValues ?: getLabelValues in drawUnderLayers) into one computation. Vico only iterates
 * the returned list -- it never mutates it -- so handing back the same cached instance across
 * calls and frames is safe.
 */
internal class DayOffsetTickCalculator(
    private val rangeDays: Int,
) {
    private val maxVal = (rangeDays - 1).toDouble()

    // Precomputed once from rangeDays, matching the pre-caching zoomedOutList branch. Only 30 and
    // 180 have a fixed 6-label list; every other rangeDays falls through to the general path.
    private val zoomedOutValues: DoubleArray? =
        when (rangeDays) {
            30 -> doubleArrayOf(0.0, 6.0, 12.0, 18.0, 24.0, 29.0)
            180 -> doubleArrayOf(0.0, 36.0, 72.0, 108.0, 144.0, 179.0)
            else -> null
        }

    // The candidate walk (0.0, spacing, 2*spacing, ... <= maxVal) depends only on spacing and
    // rangeDays (fixed per instance), so it's computed once per spacing bucket instead of per call.
    private val candidatesBySpacing = HashMap<Int, DoubleArray>()

    // Single-entry result cache. NaN never compares equal (even to itself), so the initial state
    // always misses.
    private var lastStart = Double.NaN
    private var lastEnd = Double.NaN
    private var lastResult: List<Double> = emptyList()

    fun values(visibleXRange: ClosedFloatingPointRange<Double>): List<Double> {
        val start = visibleXRange.start
        val end = visibleXRange.endInclusive
        if (start == lastStart && end == lastEnd) {
            return lastResult
        }

        val result = calculate(visibleXRange, start, end)
        lastStart = start
        lastEnd = end
        lastResult = result
        return result
    }

    private fun calculate(
        visibleXRange: ClosedFloatingPointRange<Double>,
        start: Double,
        end: Double,
    ): List<Double> {
        val visibleDays = end - start
        val buffer = 0.01

        // If mostly/fully zoomed out, use perfectly spaced 6-label lists to avoid strange jumps.
        // Only taken when a precomputed list exists for this rangeDays; otherwise fall through.
        if (visibleDays > rangeDays - 2.0 && zoomedOutValues != null) {
            return zoomedOutValues.filter { it in (start - buffer)..(end + buffer) }
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

        val candidates = candidatesBySpacing.getOrPut(spacing) { buildCandidates(spacing) }

        // Candidates are generated ascending, so the filtered result is already ascending.
        val visibleValues = ArrayList<Double>(candidates.size)
        for (candidate in candidates) {
            if (candidate in (start - buffer)..(end + buffer)) {
                visibleValues.add(candidate)
            }
        }

        // Unreachable in practice: 0.0 is always the first candidate emitted by buildCandidates
        // (current starts at 0.0), and if 0.0 is in visibleXRange it trivially survives the
        // (start - buffer)..(end + buffer) filter above, so visibleValues already contains it
        // whenever this condition's first half is true. Preserved verbatim from the pre-cache
        // implementation per the byte-identity mandate.
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
                // removeAt would throw IndexOutOfBoundsException on an empty visibleValues, but
                // that is unreachable today: TimeRange only ships {7, 30, 180}
                // (core/ui/.../common/TimeRange.kt), and ChartDefaults.rememberChartState disables
                // zoom/scroll entirely for rangeDays == 7 and floors zoom-out at
                // Zoom.min(Zoom.Content, Zoom.fixed(1f)) for 30/180, so the visible range can never
                // exceed the full domain. The smallest achievable windows (5 days at 30d/6x max
                // zoom, ~7.2 days at 180d/25x max zoom) both land in spacing = 2, where a multiple
                // of 2 is always in range, so visibleValues is never empty here. Preserved verbatim
                // from the pre-cache implementation per the byte-identity mandate. Adding a fourth
                // TimeRange value requires re-checking this invariant.
                visibleValues.removeAt(visibleValues.size - 1)
            }
            visibleValues.add(maxVal)
        }

        // No .sorted(): candidates ascending + 0.0 prepended (<= every candidate) + maxVal appended
        // (>= every candidate, since candidates are capped at maxVal) => already ascending. Proven
        // by the golden test in DayOffsetTickCalculatorTest.
        return visibleValues
    }

    private fun buildCandidates(spacing: Int): DoubleArray {
        val values = mutableListOf<Double>()
        var current = 0.0
        while (current <= maxVal) {
            values.add(current)
            current += spacing.toDouble()
        }
        return values.toDoubleArray()
    }
}
