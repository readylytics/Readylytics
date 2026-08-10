package app.readylytics.health.core.ui.common

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Latest-bucket-vs-prior-bucket summary for a bucketed trend series.
 * [average] is the latest populated bucket's average, [previousAverage] the bucket before it.
 */
data class PeriodAverageSummary(
    val periodLabel: String,
    val previousPeriodLabel: String,
    val average: Float?,
    val previousAverage: Float?,
)

private data class Bucket(
    val start: LocalDate,
    val points: List<DailyDataPoint>,
)

private fun DailyDataPoint.bucketStart(
    granularity: TrendGranularity,
    startDate: LocalDate,
): LocalDate {
    val date = startDate.plusDays(dayOffset.toLong())
    return when (granularity) {
        TrendGranularity.DAILY -> date
        TrendGranularity.MONTHLY -> date.withDayOfMonth(1)
        TrendGranularity.QUARTERLY -> {
            val quarterStartMonth = ((date.monthValue - 1) / 3) * 3 + 1
            date.withDayOfMonth(1).withMonth(quarterStartMonth)
        }
    }
}

private fun bucketLengthDays(
    bucketStart: LocalDate,
    granularity: TrendGranularity,
): Int =
    when (granularity) {
        TrendGranularity.DAILY -> 1
        TrendGranularity.MONTHLY -> bucketStart.lengthOfMonth()
        TrendGranularity.QUARTERLY ->
            ChronoUnit.DAYS.between(bucketStart, bucketStart.plusMonths(3)).toInt()
    }

/**
 * Display label for the period containing [date]: localized month abbreviation for [MONTHLY],
 * `Qn` for [QUARTERLY], or the ISO date for [DAILY]. Shared by the axis formatter and the
 * period summary builder so both render identical labels.
 */
fun periodLabelFor(
    granularity: TrendGranularity,
    date: LocalDate,
): String =
    when (granularity) {
        TrendGranularity.MONTHLY ->
            date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
        TrendGranularity.QUARTERLY -> "Q${(date.monthValue - 1) / 3 + 1}"
        TrendGranularity.DAILY -> date.toString()
    }

private fun DailyDataPoint.periodLabel(
    granularity: TrendGranularity,
    startDate: LocalDate,
): String = periodLabelFor(granularity, startDate.plusDays(dayOffset.toLong()))

/**
 * Groups [DailyDataPoint]s by calendar month or quarter (per [granularity]), averages each
 * bucket's non-null values, rounds that average to [valueDecimalPlaces], and emits one point per
 * populated bucket positioned at that bucket's midpoint calendar day offset relative to [startDate].
 * Buckets with no data are omitted entirely. `DAILY` returns the original non-null points sorted by
 * day offset (no averaging).
 * When [endDate] is supplied, midpoint positions are clamped to the selected date range so
 * partial boundary buckets remain visible.
 */
fun List<DailyDataPoint>.bucketBy(
    granularity: TrendGranularity,
    startDate: LocalDate,
    endDate: LocalDate? = null,
    valueDecimalPlaces: Int = 0,
): List<DailyDataPoint> {
    val present = filter { it.value != null }
    if (granularity == TrendGranularity.DAILY) {
        return present.sortedBy(DailyDataPoint::dayOffset)
    }

    val buckets =
        present
            .groupBy { it.bucketStart(granularity, startDate) }
            .map { (bucketStart, points) -> Bucket(bucketStart, points) }
            .sortedBy(Bucket::start)

    return buckets.map { bucket ->
        val length = bucketLengthDays(bucket.start, granularity)
        val midpoint = bucket.start.plusDays(((length - 1) / 2).toLong())
        val mid = endDate?.let { midpoint.coerceIn(startDate, it) } ?: midpoint
        val average =
            bucket.points
                .mapNotNull(DailyDataPoint::value)
                .average()
                .toFloat()
                .roundToDecimalPlaces(valueDecimalPlaces)
        DailyDataPoint(ChronoUnit.DAYS.between(startDate, mid).toInt(), average)
    }
}

private fun Float.roundToDecimalPlaces(decimalPlaces: Int): Float {
    val factor = 10f.pow(decimalPlaces)
    return (this * factor).roundToInt() / factor
}

/**
 * Builds the latest-bucket-vs-prior-bucket summary from a bucketed series ([points] must be the
 * ascending output of [bucketBy]). Returns null unless at least two populated buckets exist.
 */
fun buildPeriodAverageSummary(
    points: List<DailyDataPoint>,
    granularity: TrendGranularity,
    startDate: LocalDate,
): PeriodAverageSummary? {
    if (points.size < 2) return null
    val latest = points.last()
    val previous = points[points.lastIndex - 1]
    return PeriodAverageSummary(
        periodLabel = latest.periodLabel(granularity, startDate),
        previousPeriodLabel = previous.periodLabel(granularity, startDate),
        average = latest.value,
        previousAverage = previous.value,
    )
}
