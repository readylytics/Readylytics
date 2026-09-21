package app.readylytics.health.testutil

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.floor

object SeedConstants {
    const val RHR_NADIR_BPM = 48L
    const val HRV_RMSSD_MIN_MS = 45.0
    const val SLEEP_BASE_HOUR_UTC = 23
    const val NADIR_TARGET_HOUR_UTC = 3
    const val RHR_PER_HOUR = 10
    const val HRV_PER_HOUR = 1
    const val DEFAULT_AVG_SLEEP_HOURS = 8.0
    const val DEFAULT_VARIATION_HOURS = 1.0

    // Mean of cycle = 0 → mean sleepDuration = avgSleepHours across any window whose length
    // is a multiple of 5. For DAYS_5/DAYS_10/DAYS_15/etc., mean is exact.
    // DAYS_14/DAYS_42 are not multiples of 5 but are close enough for the ±0.01h tolerance.
    private val VARIATION_CYCLE = listOf(-1.0, -0.5, 0.0, 0.5, 1.0)

    // Offset in minutes from SLEEP_BASE_HOUR_UTC (23:00 UTC). Sums to 0 → mean = 0.
    private val BEDTIME_OFFSET_MINUTES_CYCLE = listOf(0, 15, 30, -15, -30)

    fun sleepDurationForDay(
        dayIndex: Int,
        avgHours: Double = DEFAULT_AVG_SLEEP_HOURS,
        variationHours: Double = DEFAULT_VARIATION_HOURS,
    ): Double = avgHours + VARIATION_CYCLE[dayIndex % VARIATION_CYCLE.size] * variationHours

    /**
     * Returns the Instant when sleep starts for [dayIndex].
     * dayIndex=0 → last night; dayIndex=1 → night before that, etc.
     *
     * Anchor: (today − dayIndex − 1) at 23:00 UTC ± bedtime offset.
     * Ensures 3:00 AM UTC (nadir target) always falls inside the sleep window.
     */
    fun sleepStartForDay(
        dayIndex: Int,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Instant {
        val eveningDate = today.minusDays(dayIndex.toLong() + 1)
        val baseBedtime = eveningDate.atTime(SLEEP_BASE_HOUR_UTC, 0).toInstant(ZoneOffset.UTC)
        val offsetMinutes = BEDTIME_OFFSET_MINUTES_CYCLE[dayIndex % BEDTIME_OFFSET_MINUTES_CYCLE.size].toLong()
        return baseBedtime.plusSeconds(offsetMinutes * 60L)
    }

    /** Deterministic nadir BPM for a given night. Minimum is [RHR_NADIR_BPM] at dayIndex=0. */
    fun rhrNadirForDay(dayIndex: Int): Long = RHR_NADIR_BPM + (dayIndex % 5).toLong()

    /** Deterministic base HRV RMSSD for a given night. Minimum is [HRV_RMSSD_MIN_MS] at dayIndex=0. */
    fun hrvRmssdForDay(dayIndex: Int): Double = HRV_RMSSD_MIN_MS + (dayIndex % 7) * 2.5

    fun expectedRhrCount(
        period: SeedPeriod,
        avgHours: Double = DEFAULT_AVG_SLEEP_HOURS,
        variationHours: Double = DEFAULT_VARIATION_HOURS,
    ): Int =
        (0 until period.days).sumOf {
            floor(sleepDurationForDay(it, avgHours, variationHours)).toInt() * RHR_PER_HOUR
        }

    fun expectedHrvCount(
        period: SeedPeriod,
        avgHours: Double = DEFAULT_AVG_SLEEP_HOURS,
        variationHours: Double = DEFAULT_VARIATION_HOURS,
    ): Int =
        (0 until period.days).sumOf {
            floor(sleepDurationForDay(it, avgHours, variationHours)).toInt() * HRV_PER_HOUR
        }

    fun meanSleepDuration(
        period: SeedPeriod,
        avgHours: Double = DEFAULT_AVG_SLEEP_HOURS,
        variationHours: Double = DEFAULT_VARIATION_HOURS,
    ): Double =
        (0 until period.days)
            .map { sleepDurationForDay(it, avgHours, variationHours) }
            .average()
}
