package app.readylytics.health.core.scoring.domain.scoring

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object CircadianTimeMath {
    const val MINUTES_PER_DAY = 1440
    private const val HALF_DAY_MINUTES = 720
    private const val TWO_PI = 2.0 * PI

    fun minuteOfDay(epochMs: Long, zone: ZoneId): Int {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
        return zdt.hour * 60 + zdt.minute
    }

    fun circularDeviationMinutes(minuteA: Int, minuteB: Int): Int {
        val diff = (minuteA - minuteB).mod(MINUTES_PER_DAY)
        return if (diff > HALF_DAY_MINUTES) MINUTES_PER_DAY - diff else diff
    }

    fun circularSignedOffsetMinutes(minuteA: Int, minuteB: Int): Int {
        val diff = (minuteA - minuteB).mod(MINUTES_PER_DAY)
        return if (diff > HALF_DAY_MINUTES) diff - MINUTES_PER_DAY else diff
    }

    fun circularMedianMinutes(minutes: List<Int>): Int =
        when {
            minutes.isEmpty() -> 0
            minutes.size == 1 -> minutes.first().mod(MINUTES_PER_DAY)
            else -> computeCircularMedian(minutes)
        }

    private fun computeCircularMedian(minutes: List<Int>): Int {
        val meanMinute = computeCircularMeanMinute(minutes)
        return if (meanMinute != null) {
            val offsets = minutes.map { circularSignedOffsetMinutes(it, meanMinute) }.sorted()
            val mid = offsets.size / 2
            val medianOffset =
                if (offsets.size % 2 == 0) {
                    (offsets[mid - 1] + offsets[mid]) / 2
                } else {
                    offsets[mid]
                }
            (meanMinute + medianOffset).mod(MINUTES_PER_DAY)
        } else {
            minutes.first().mod(MINUTES_PER_DAY)
        }
    }

    private fun computeCircularMeanMinute(minutes: List<Int>): Int? {
        var sumSin = 0.0
        var sumCos = 0.0
        for (m in minutes) {
            val rad = TWO_PI * (m.mod(MINUTES_PER_DAY)) / MINUTES_PER_DAY.toDouble()
            sumSin += sin(rad)
            sumCos += cos(rad)
        }

        if (sumSin == 0.0 && sumCos == 0.0) return null

        val meanAngle = atan2(sumSin, sumCos)
        val meanMinuteDouble = (meanAngle / TWO_PI * MINUTES_PER_DAY).mod(MINUTES_PER_DAY.toDouble())
        return meanMinuteDouble.roundToInt().mod(MINUTES_PER_DAY)
    }
}
