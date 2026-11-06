package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.ZoneId

/**
 * Shared x-axis scaffolding for period-aggregated Vico trend charts (sleep trend, ACWR, TSB).
 * [DAILY][TrendGranularity.DAILY] keeps real day offsets; any coarser granularity renders one
 * point per period at ordinal x positions `0..n-1` with a label per period.
 */

/** Re-index [points] to ordinal x positions for non-daily granularities; daily is returned as-is. */
fun remapToOrdinalOffsets(
    points: List<DailyDataPoint>,
    granularity: TrendGranularity,
): List<DailyDataPoint> =
    if (granularity == TrendGranularity.DAILY) points else points.mapIndexed { i, p -> p.copy(dayOffset = i) }

@Composable
fun rememberOrdinalRemappedPoints(
    points: List<DailyDataPoint>,
    granularity: TrendGranularity,
): List<DailyDataPoint> = remember(points, granularity) { remapToOrdinalOffsets(points, granularity) }

/** One label per point for non-daily granularities, resolved in [zoneId]; empty for daily. */
fun periodLabelsFor(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
    zoneId: ZoneId,
    ordinalLabel: (Int) -> String,
): List<String> =
    if (granularity == TrendGranularity.DAILY) {
        emptyList()
    } else {
        points.map { point ->
            val date = ChartUtils.dayOffsetToLocalDate(point.dayOffset, rangeStartMs, zoneId)
            periodLabelFor(granularity, date, ordinalLabel)
        }
    }

@Composable
fun rememberPeriodLabels(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<String> {
    val ordinalLabel = rememberPeriodOrdinalLabel(granularity)
    return remember(points, rangeStartMs, granularity, zoneId, ordinalLabel) {
        periodLabelsFor(points, rangeStartMs, granularity, zoneId, ordinalLabel)
    }
}

/** Maps an ordinal x value to its label; out-of-range values fall back to the first label (or ""). */
fun periodLabelFormatter(periodLabels: List<String>): CartesianValueFormatter {
    val fallback = periodLabels.firstOrNull().orEmpty()
    return CartesianValueFormatter { _, value, _ -> periodLabels.getOrElse(value.toInt()) { fallback } }
}

@Composable
fun rememberPeriodAxisFormatter(
    periodLabels: List<String>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
    zoneId: ZoneId = ZoneId.systemDefault(),
): CartesianValueFormatter =
    if (granularity == TrendGranularity.DAILY) {
        ChartDefaults.rememberPeriodFormatter(rangeStartMs, granularity, zoneId)
    } else {
        remember(periodLabels) { periodLabelFormatter(periodLabels) }
    }

/** Range provider with x pinned to `0..(xAxisRangeDays - 1)` and y delegated to [minYOf] / [maxYOf]. */
fun fixedXRangeProvider(
    xAxisRangeDays: Int,
    minYOf: (minY: Double, maxY: Double) -> Double,
    maxYOf: (minY: Double, maxY: Double) -> Double,
): CartesianLayerRangeProvider =
    object : CartesianLayerRangeProvider {
        override fun getMinX(
            minX: Double,
            maxX: Double,
            extraStore: ExtraStore,
        ) = 0.0

        override fun getMaxX(
            minX: Double,
            maxX: Double,
            extraStore: ExtraStore,
        ) = (xAxisRangeDays - 1).toDouble()

        override fun getMinY(
            minY: Double,
            maxY: Double,
            extraStore: ExtraStore,
        ) = minYOf(minY, maxY)

        override fun getMaxY(
            minY: Double,
            maxY: Double,
            extraStore: ExtraStore,
        ) = maxYOf(minY, maxY)
    }
