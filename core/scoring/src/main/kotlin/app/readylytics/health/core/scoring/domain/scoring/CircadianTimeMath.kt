package app.readylytics.health.core.scoring.domain.scoring

import java.time.Instant
import java.time.ZoneId

object CircadianTimeMath {
    const val MINUTES_PER_DAY = 1440
    private const val MINUTES_PER_HOUR = 60
    private const val HALF_DAY_MINUTES = 720

    fun minuteOfDay(epochMs: Long, zone: ZoneId): Int {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
        return zdt.hour * MINUTES_PER_HOUR + zdt.minute
    }

    fun circularDeviationMinutes(minuteA: Int, minuteB: Int): Int {
        val diff = (minuteA - minuteB).mod(MINUTES_PER_DAY)
        return if (diff > HALF_DAY_MINUTES) MINUTES_PER_DAY - diff else diff
    }

    fun circularSignedOffsetMinutes(minuteA: Int, minuteB: Int): Int {
        val diff = (minuteA - minuteB).mod(MINUTES_PER_DAY)
        return if (diff > HALF_DAY_MINUTES) diff - MINUTES_PER_DAY else diff
    }

    /**
     * Circular median: the sample that minimizes total circular distance to all samples (ties
     * resolved to the smallest minute, so the result is deterministic) is used as a local origin,
     * and the median of signed offsets from that origin is added back. Unlike a mean-based origin
     * this stays stable for spread-out or bimodal inputs where the resultant vector cancels out.
     */
    fun circularMedianMinutes(minutes: List<Int>): Int =
        when {
            minutes.isEmpty() -> 0
            minutes.size == 1 -> minutes.first().mod(MINUTES_PER_DAY)
            else -> computeCircularMedian(minutes.map { it.mod(MINUTES_PER_DAY) })
        }

    private fun computeCircularMedian(minutes: List<Int>): Int {
        val origin =
            minutes
                .distinct()
                .sorted()
                .minBy { candidate -> minutes.sumOf { circularDeviationMinutes(it, candidate) } }
        val offsets = minutes.map { circularSignedOffsetMinutes(it, origin) }.sorted()
        val mid = offsets.size / 2
        val medianOffset =
            if (offsets.size % 2 == 0) {
                (offsets[mid - 1] + offsets[mid]) / 2
            } else {
                offsets[mid]
            }
        return (origin + medianOffset).mod(MINUTES_PER_DAY)
    }
}
