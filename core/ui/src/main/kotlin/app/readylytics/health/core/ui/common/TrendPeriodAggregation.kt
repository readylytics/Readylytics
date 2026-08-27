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

/**
 * A non-overlapping, fixed-length day-bucket used only by the Vitals baseline overlay at 7D/30D.
 * Buckets are anchored at day-offset 0 (the caller's `startDate`) rather than at a calendar
 * boundary, so pairing is deterministic regardless of which real-world dates the window spans.
 * [lastDayOffset] is the x-axis position of the bucket's point (the bucket's last day), which for
 * the final bucket equals the window's last day so the overlay line reaches it.
 */
data class FixedDayBucket(
    val startDayOffset: Int,
    val endDayOffsetExclusive: Int,
    val lastDayOffset: Int,
    val value: Float,
)

/**
 * Groups [DailyDataPoint]s into non-overlapping [bucketSizeDays]-day windows anchored at day
 * offset 0, averaging each bucket's non-null values and rounding to [valueDecimalPlaces].
 * Buckets with zero non-null values are omitted entirely, mirroring [bucketBy]'s null-filtering
 * convention. [bucketSizeDays] == 1 yields one bucket per populated day (no averaging).
 * [rangeEndOffsetExclusive] clips the final bucket's end boundary to the selected window. Each
 * bucket's point is positioned at the bucket's last day ([FixedDayBucket.lastDayOffset]), so the
 * final bucket's point lands on the window's last day.
 */
fun List<DailyDataPoint>.bucketByFixedSize(
    bucketSizeDays: Int,
    rangeEndOffsetExclusive: Int,
    valueDecimalPlaces: Int = 0,
): List<FixedDayBucket> {
    require(bucketSizeDays >= 1) { "bucketSizeDays must be >= 1" }
    return filter { it.value != null }
        .groupBy { it.dayOffset / bucketSizeDays }
        .toSortedMap()
        .map { (bucketIndex, points) ->
            val startOffset = bucketIndex * bucketSizeDays
            val endOffsetExclusive = (startOffset + bucketSizeDays).coerceAtMost(rangeEndOffsetExclusive)
            val average =
                points
                    .mapNotNull(DailyDataPoint::value)
                    .average()
                    .toFloat()
                    .roundToDecimalPlaces(valueDecimalPlaces)
            FixedDayBucket(startOffset, endOffsetExclusive, endOffsetExclusive - 1, average)
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

fun allBucketOffsets(
    granularity: TrendGranularity,
    startDate: LocalDate,
    endDate: LocalDate,
): List<Int> {
    if (granularity == TrendGranularity.DAILY) return emptyList()
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
    return offsets
}

fun List<DailyDataPoint>.padBucketsToRange(
    granularity: TrendGranularity,
    startDate: LocalDate,
    endDate: LocalDate,
): List<DailyDataPoint> {
    if (granularity == TrendGranularity.DAILY) return this
    val byOffset = this.associateBy { it.dayOffset }
    val offsets = allBucketOffsets(granularity, startDate, endDate)
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
