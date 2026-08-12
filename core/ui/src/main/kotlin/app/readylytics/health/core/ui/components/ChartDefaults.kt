package app.readylytics.health.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.allBucketOffsets
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
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
import kotlin.math.roundToInt

object ChartDefaults {
    /**
     * Maximum number of x-axis ticks rendered on the 360D (EIGHT_WEEK) range. Capping keeps the dense
     * 8-week labels from crowding into truncation; surplus periods are dropped via an evenly-spaced
     * subsample (see [subsampleTicks]). 180D (MONTHLY) is intentionally NOT capped so every month is
     * labelled.
     */
    const val MAX_X_AXIS_TICKS: Int = 4

    /**
     * Generic remembered x-axis item placer for trend charts over a [rangeDays] window starting at
     * [rangeStartMs], bucketed per [granularity].
     *
     * DAILY charts delegate to the zoom-aware [DayOffsetTickCalculator]. Bucketed charts place one
     * candidate tick per calendar period via [allBucketOffsets]; the 360D (EIGHT_WEEK) range caps
     * the rendered count to [MAX_X_AXIS_TICKS] with equal spacing, while 180D (MONTHLY) keeps every
     * period. [explicitPointOffsets] overrides the derived candidates for charts whose x-domain is
     * remapped to compact indices (sleep/workouts).
     */
    @Composable
    fun rememberTrendAxisItemPlacer(
        rangeDays: Int,
        granularity: TrendGranularity,
        rangeStartMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        explicitPointOffsets: List<Int>? = null,
    ): HorizontalAxis.ItemPlacer =
        remember(rangeDays, granularity, rangeStartMs, zoneId, explicitPointOffsets) {
            val pointOffsets =
                explicitPointOffsets
                    ?: if (granularity == TrendGranularity.DAILY) {
                        emptyList()
                    } else {
                        val startDate = Instant.ofEpochMilli(rangeStartMs).atZone(zoneId).toLocalDate()
                        val endDate = startDate.plusDays((rangeDays - 1).toLong())
                        allBucketOffsets(granularity, startDate, endDate)
                    }
            itemPlacerForRangeDays(
                rangeDays = rangeDays,
                pointOffsets = pointOffsets,
                maxTicks = if (granularity == TrendGranularity.EIGHT_WEEK) MAX_X_AXIS_TICKS else Int.MAX_VALUE,
            )
        }

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
     * [MONTHLY] and [EIGHT_WEEK] resolve each day offset to its calendar date in [zoneId] and
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
        // Resolved outside remember{} so the formatter lambda can capture it; resource strings
        // must never be built as Kotlin literals.
        val ordinalLabel = rememberPeriodOrdinalLabel(granularity)
        return remember(rangeStartMs, granularity, zoneId, ordinalLabel) {
            val baseDate = Instant.ofEpochMilli(rangeStartMs).atZone(zoneId).toLocalDate()
            CartesianValueFormatter { _, value, _ ->
                periodLabelFor(granularity, baseDate.plusDays(value.toLong()), ordinalLabel)
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
     * Single tick source for all Vico horizontal axes. With [pointOffsets] non-empty (bucketed
     * monthly/quarterly charts) it places one tick per actual plotted point offset. With empty
     * [pointOffsets] (daily charts) it delegates to [DayOffsetTickCalculator], which owns the
     * zoom-aware spacing/caching for `0..rangeDays-1` domains.
     */
    internal fun tickValuesFor(
        rangeDays: Int,
        pointOffsets: List<Int>,
        visibleXRange: ClosedFloatingPointRange<Double>,
    ): List<Double> =
        if (pointOffsets.isEmpty()) {
            DayOffsetTickCalculator(rangeDays).values(visibleXRange)
        } else {
            pointOffsets
                .sorted()
                .distinct()
                .map { it.toDouble() }
                .filter { it in visibleXRange }
        }

    /**
     * The returned placer is stateful: the daily branch owns a [DayOffsetTickCalculator] holding
     * per-instance candidate and single-entry result caches. Callers must scope it to a single chart
     * via `remember(rangeDays, pointOffsets) { ChartDefaults.itemPlacerForRangeDays(...) }` —
     * constructing it inline on every recomposition silently discards the caching (no test failure,
     * no visual difference, just the optimization evaporating).
     *
     * [maxTicks] caps the number of bucketed x-axis ticks. When the visible tick list exceeds it,
     * the list is subsampled evenly (first and last preserved) so labels never crowd into truncation
     * on dense ranges like 360D. DAILY charts ignore it (they already own zoom-aware spacing).
     */
    fun itemPlacerForRangeDays(
        rangeDays: Int,
        pointOffsets: List<Int> = emptyList(),
        maxTicks: Int = Int.MAX_VALUE,
    ): HorizontalAxis.ItemPlacer {
        val basePlacer =
            HorizontalAxis.ItemPlacer.aligned(
                spacing = { 1 },
                addExtremeLabelPadding = true,
            )

        // Per-instance caches are safe here: Vico measures and draws on a single thread, this placer
        // is scoped to one chart via remember(rangeDays, pointOffsets) at every call site, and Vico
        // only iterates the returned list (HorizontalAxis.kt:184-185 and :294-295 in Vico 3.2.3) --
        // it never mutates it, so handing the same cached instance to getLabelValues, getLineValues,
        // and consecutive frames is safe. A single frame asks three times with the same visible range.
        // The bucketed branch has no cache: its point-offset list is already the tick list.
        val dailyTicks = if (pointOffsets.isEmpty()) DayOffsetTickCalculator(rangeDays) else null
        val pointTicks =
            if (pointOffsets.isEmpty()) {
                null
            } else {
                pointOffsets.sorted().distinct().map { it.toDouble() }
            }

        fun bounded(visibleTicks: List<Double>): List<Double> =
            if (pointTicks == null || visibleTicks.size <= maxTicks) {
                visibleTicks
            } else {
                subsampleTicks(visibleTicks, maxTicks)
            }

        return object : HorizontalAxis.ItemPlacer by basePlacer {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> =
                if (pointTicks != null) {
                    bounded(pointTicks.filter { it in visibleXRange })
                } else {
                    requireNotNull(dailyTicks).values(visibleXRange)
                }

            override fun getLineValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> =
                if (pointTicks != null) {
                    bounded(pointTicks.filter { it in visibleXRange })
                } else {
                    requireNotNull(dailyTicks).values(visibleXRange)
                }
        }
    }

    /**
     * Caps [ticks] to [maxTicks] by distributing them evenly across the span in *value* (day-offset)
     * space, always preserving the first and last so the axis keeps its start/end anchors. Input must
     * be sorted ascending; when [ticks] already fits within the cap it is returned unchanged.
     *
     * Distributing by value (not by index) matters because bucket midpoints are not uniformly
     * spaced — EIGHT_WEEK octads can be 56 or 35 days long — so an index-based subsample would leave
     * visibly uneven gaps. Rounded to whole day offsets so labels map to clean dates.
     */
    internal fun subsampleTicks(
        ticks: List<Double>,
        maxTicks: Int,
    ): List<Double> {
        if (ticks.size <= maxTicks) return ticks
        val step = (ticks.last() - ticks.first()) / (maxTicks - 1)
        return List(maxTicks) { index -> (ticks.first() + index * step).roundToInt().toDouble() }
    }
}
