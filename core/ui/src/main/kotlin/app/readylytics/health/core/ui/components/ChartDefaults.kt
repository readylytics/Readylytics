package app.readylytics.health.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object ChartDefaults {
    @Composable
    fun labelTextComponent(): TextComponent =
        rememberTextComponent(
            style = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        )

    @Composable
    fun axisLabelTextComponent(): TextComponent =
        rememberTextComponent(
            style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )

    @Composable
    fun guidelineComponent(): LineComponent =
        rememberLineComponent(
            fill = Fill(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
            thickness = 1.dp,
        )

    @Composable
    fun rememberDayOffsetFormatter(rangeStartMs: Long): CartesianValueFormatter =
        rememberDayOffsetFormatter(rangeStartMs, ZoneId.systemDefault())

    @Composable
    fun rememberDayOffsetFormatter(
        rangeStartMs: Long,
        zoneId: ZoneId,
    ): CartesianValueFormatter =
        remember(rangeStartMs, zoneId) {
            val labels = DayOffsetLabelCache(rangeStartMs, zoneId)
            CartesianValueFormatter { _, value, _ -> labels.label(value) }
        }

    /**
     * Granularity-aware x-axis formatter. [DAILY] delegates to [rememberDayOffsetFormatter];
     * [MONTHLY] and [QUARTERLY] resolve each day offset to its calendar date in [zoneId] and
     * format the containing period via [periodLabelFor].
     */
    @Composable
    fun rememberPeriodFormatter(
        rangeStartMs: Long,
        granularity: TrendGranularity,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): CartesianValueFormatter {
        if (granularity == TrendGranularity.DAILY) {
            return rememberDayOffsetFormatter(rangeStartMs, zoneId)
        }
        // Resolved outside remember{} so the format string can be used inside the non-composable
        // formatter lambda; resource strings must never be built as Kotlin literals.
        val quarterTemplate = stringResource(R.string.period_label_quarter)
        return remember(rangeStartMs, granularity, zoneId, quarterTemplate) {
            val baseDate = Instant.ofEpochMilli(rangeStartMs).atZone(zoneId).toLocalDate()
            CartesianValueFormatter { _, value, _ ->
                periodLabelFor(
                    granularity,
                    baseDate.plusDays(value.toLong()),
                ) { quarter -> String.format(Locale.getDefault(), quarterTemplate, quarter) }
            }
        }
    }

    @Composable
    fun rememberChartState(
        rangeDays: Int,
        key: Any,
    ): Pair<VicoScrollState, VicoZoomState> =
        key(key) {
            val scrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7)
            val zoomState =
                rememberVicoZoomState(
                    zoomEnabled = rangeDays > 7,
                    initialZoom = Zoom.Content,
                    // minZoom = Zoom.min(Zoom.Content, Zoom.fixed(1f)): floor the zoom-out at
                    // whichever is smaller — the content zoom (fits all data) or 1×. For 30d
                    // (~0.86×) and 180d (~0.14×) the content zoom wins, so the floor equals the
                    // initial fit-to-range view: the user can never zoom out past it and the
                    // x-axis never reveals empty space / future dates beyond the latest point.
                    // Mixing via Zoom.min (vs. a bare Zoom.Content floor) avoids the circular
                    // constraint that silently rejects pinch-in gestures.
                    minZoom = Zoom.min(Zoom.Content, Zoom.fixed(1f)),
                    maxZoom =
                        remember(rangeDays) {
                            when (rangeDays) {
                                30 -> Zoom.fixed(6f)
                                180 -> Zoom.fixed(25f)
                                360 -> Zoom.fixed(45f)
                                else -> Zoom.fixed(2f)
                            }
                        },
                )
            scrollState to zoomState
        }

    /**
     * The returned placer is stateful: it owns a [DayOffsetTickCalculator] holding per-instance
     * candidate and single-entry result caches. Callers must scope it to a single chart via
     * `remember(rangeDays) { ChartDefaults.itemPlacerForRangeDays(rangeDays) }` — constructing it
     * inline on every recomposition silently discards the caching (no test failure, no visual
     * difference, just the optimization evaporating).
     */
    fun itemPlacerForRangeDays(rangeDays: Int): HorizontalAxis.ItemPlacer {
        val basePlacer =
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { 1 },
                addExtremeLabelPadding = true,
            )

        // Per-instance caches are safe here: Vico measures and draws on a single thread, this placer
        // is scoped to one chart via remember(rangeDays) at every call site, and Vico only iterates
        // the returned list (HorizontalAxis.kt:184-185 and :294-295 in Vico 3.2.3) -- it never mutates
        // it, so handing the same cached instance to getLabelValues, getLineValues, and consecutive
        // frames is safe. A single frame asks three times with the same visible range.
        val ticks = DayOffsetTickCalculator(rangeDays)

        return object : HorizontalAxis.ItemPlacer by basePlacer {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = ticks.values(visibleXRange)

            override fun getLineValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = ticks.values(visibleXRange)
        }
    }

    /**
     * Places one tick per actual plotted point offset (used for bucketed monthly/quarterly charts,
     * where there are ~12 or ~4 points instead of up to 180/360 daily ones). Intentionally does not
     * reuse [DayOffsetTickCalculator]: its spacing-invariant assumes daily ranges and "TimeRange
     * only ships {7, 30, 180}", which adding [app.readylytics.health.core.ui.common.TimeRange.TWELVE_MONTHS]
     * would violate.
     */
    fun itemPlacerForPoints(pointOffsets: List<Int>): HorizontalAxis.ItemPlacer {
        val basePlacer =
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { 1 },
                addExtremeLabelPadding = true,
            )
        val ticks = pointOffsets.sorted().distinct().map { it.toDouble() }

        return object : HorizontalAxis.ItemPlacer by basePlacer {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = ticks.filter { it in visibleXRange }

            override fun getLineValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = ticks.filter { it in visibleXRange }
        }
    }
}
