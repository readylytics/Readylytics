package app.readylytics.health.core.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Latest-bucket-vs-prior-bucket summary for a bucketed trend series.
 * [average] is the latest populated bucket's average, [previousAverage] the bucket before it.
 * Labels are intentionally not preformatted: [periodStartDate]/[previousPeriodStartDate] carry the
 * bucket midpoint dates so the UI layer can format them (quarter labels come from `strings.xml`).
 */
data class PeriodAverageSummary(
    val granularity: TrendGranularity,
    val periodStartDate: LocalDate,
    val previousPeriodStartDate: LocalDate,
    val average: Float?,
    val previousAverage: Float?,
)

private data class Bucket(
    val start: LocalDate,
    val points: List<DailyDataPoint>,
)

fun bucketStartForDate(
    date: LocalDate,
    granularity: TrendGranularity,
): LocalDate =
    when (granularity) {
        TrendGranularity.DAILY -> date
        TrendGranularity.MONTHLY -> date.withDayOfMonth(1)
        TrendGranularity.EIGHT_WEEK -> {
            val weekBasedYear = date.get(IsoFields.WEEK_BASED_YEAR)
            val isoWeek = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val octadFirstWeek = ((isoWeek - 1) / 8) * 8 + 1
            date
                .with(IsoFields.WEEK_BASED_YEAR, weekBasedYear.toLong())
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, octadFirstWeek.toLong())
                .with(DayOfWeek.MONDAY)
        }
    }

private fun bucketMidpointOffset(
    bucketStart: LocalDate,
    granularity: TrendGranularity,
    startDate: LocalDate,
    endDate: LocalDate?,
): Int {
    val length = bucketLengthDays(bucketStart, granularity)
    val midpoint = bucketStart.plusDays(((length - 1) / 2).toLong())
    val mid = endDate?.let { midpoint.coerceIn(startDate, it) } ?: midpoint
    return ChronoUnit.DAYS.between(startDate, mid).toInt()
}

private fun DailyDataPoint.bucketStart(
    granularity: TrendGranularity,
    startDate: LocalDate,
): LocalDate = bucketStartForDate(startDate.plusDays(dayOffset.toLong()), granularity)

fun bucketLengthDays(
    bucketStart: LocalDate,
    granularity: TrendGranularity,
): Int =
    when (granularity) {
        TrendGranularity.DAILY -> 1
        TrendGranularity.MONTHLY -> bucketStart.lengthOfMonth()
        TrendGranularity.EIGHT_WEEK -> {
            val nextYearStart =
                bucketStart
                    .with(IsoFields.WEEK_BASED_YEAR, bucketStart.get(IsoFields.WEEK_BASED_YEAR) + 1L)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1)
                    .with(DayOfWeek.MONDAY)
            ChronoUnit.DAYS
                .between(bucketStart, nextYearStart)
                .toInt()
                .coerceAtMost(56)
        }
    }

/**
 * Display label for the period containing [date]: localized month abbreviation for [MONTHLY],
 * [ordinalLabel] for [EIGHT_WEEK], or the ISO date for [DAILY]. The ordinal label must be
 * produced by the caller (it carries a `strings.xml` resource), keeping this function pure.
 */
fun periodLabelFor(
    granularity: TrendGranularity,
    date: LocalDate,
    ordinalLabel: (Int) -> String,
): String =
    when (granularity) {
        TrendGranularity.MONTHLY ->
            date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
        TrendGranularity.EIGHT_WEEK -> ordinalLabel(date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        TrendGranularity.DAILY -> date.toString()
    }

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
        val average =
            bucket.points
                .mapNotNull(DailyDataPoint::value)
                .average()
                .toFloat()
                .roundToDecimalPlaces(valueDecimalPlaces)
        DailyDataPoint(bucketMidpointOffset(bucket.start, granularity, startDate, endDate), average)
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
        granularity = granularity,
        periodStartDate = startDate.plusDays(latest.dayOffset.toLong()),
        previousPeriodStartDate = startDate.plusDays(previous.dayOffset.toLong()),
        average = latest.value,
        previousAverage = previous.value,
    )
}

fun List<DailyDataPoint>.aggregateByRange(
    granularity: TrendGranularity,
    startDate: LocalDate,
    endDate: LocalDate,
    rangeDays: Int,
    valueDecimalPlaces: Int = 0,
): Pair<List<DailyDataPoint>, PeriodAverageSummary?> =
    if (granularity == TrendGranularity.DAILY) {
        this.padToRange(rangeDays) to null
    } else {
        val bucketed = this.bucketBy(granularity, startDate, endDate, valueDecimalPlaces = valueDecimalPlaces)
        bucketed to buildPeriodAverageSummary(bucketed, granularity, startDate)
    }

fun List<DailyDataPoint>.padBucketsToRange(
    granularity: TrendGranularity,
    startDate: LocalDate,
    endDate: LocalDate,
): List<DailyDataPoint> {
    if (granularity == TrendGranularity.DAILY) return this
    val byOffset = this.associateBy { it.dayOffset }
    val offsets = mutableListOf<Int>()
    var cursor = startDate
    while (!cursor.isAfter(endDate)) {
        val bucketStart = bucketStartForDate(cursor, granularity)
        val offset = bucketMidpointOffset(bucketStart, granularity, startDate, endDate)
        if (offset !in offsets) offsets.add(offset)
        cursor =
            when (granularity) {
                TrendGranularity.MONTHLY -> bucketStart.plusMonths(1)
                TrendGranularity.EIGHT_WEEK -> bucketStart.plusWeeks(8)
            }
    }
    return offsets.map { offset -> byOffset[offset] ?: DailyDataPoint(offset, null) }
}

/**
 * Resolves the ordinal period label formatter for [granularity] from `strings.xml`: "Wk %1$d"
 * for [TrendGranularity.EIGHT_WEEK], "Q%1$d" otherwise. Keeps the label template resolution
 * out of the seven call sites that previously repeated the same `stringResource` + `String.format`
 * boilerplate.
 */
@Composable
fun rememberPeriodOrdinalLabel(granularity: TrendGranularity): (Int) -> String {
    val quarterTemplate = stringResource(R.string.period_label_quarter)
    val weekTemplate = stringResource(R.string.label_week_short)
    val template = if (granularity == TrendGranularity.EIGHT_WEEK) weekTemplate else quarterTemplate
    return remember(template) {
        { ordinal: Int -> String.format(Locale.getDefault(), template, ordinal) }
    }
}
