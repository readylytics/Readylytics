package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.scoring.domain.workouts.weekly.DailyTrainingVolume
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.InvisibleMarker
import app.readylytics.health.core.ui.components.VicoChartTooltipOverlay
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.format.TextStyle
import kotlin.math.ceil
import app.readylytics.health.core.ui.R as CoreUiR

private const val DAYS_IN_WEEK = 7
private const val CHART_HEIGHT_DP = 200
private const val Y_AXIS_STEP_MINUTES = 30.0
private const val Y_AXIS_FLOOR_MINUTES = 60.0

/**
 * Matches the rendered card: 32dp vertical padding + title + 200dp chart + legend, so the skeleton
 * does not resize when data lands.
 */
private const val SKELETON_HEIGHT_DP = 292

@Composable
fun WeeklyVolumeTrendChartCard(
    stats: WeeklyTrainingStats?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
    if (isLoading || stats == null) {
        SkeletonCard(height = SKELETON_HEIGHT_DP.dp, modifier = modifier.fillMaxWidth())
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = stringResource(R.string.weekly_volume_trend_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            val daily = stats.cumulativeDailyTraining
            val hasAnyData =
                daily.any { (it.currentWeekDurationMinutes ?: 0) > 0 || it.previousWeekDurationMinutes > 0 }
            if (hasAnyData) {
                WeeklyVolumeTrendChart(daily = daily, parentScrollInProgress = parentScrollInProgress)
            } else {
                EmptyWeeklyVolumeTrendPlaceholder()
            }
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            WeeklyVolumeTrendLegend()
        }
    }
}

@Composable
private fun WeeklyVolumeTrendChart(
    daily: List<DailyTrainingVolume>,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
    var selectedState by remember(daily) { mutableStateOf<WeeklyVolumeSelectedState?>(null) }

    // Dismiss the tooltip/selection when the parent list scrolls vertically, so the popup never
    // detaches from its anchor point. The chart itself has scrolling and zooming disabled.
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) selectedState = null
        }
    }

    val series = remember(daily) { WeeklyVolumeTrendMapper.toSeries(daily) }
    val currentPoints = series.first
    val previousPoints = series.second
    val todayOffset = remember(daily) { WeeklyVolumeTrendMapper.todayOffset(daily) }
    val locale = LocalLocale.current.platformLocale
    val weekdayLabels =
        remember(daily, locale) {
            daily.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }
        }

    val modelProducer = rememberWeeklyVolumeModelProducer(currentPoints, previousPoints)
    val (currentLine, previousLine) = rememberWeeklyVolumeLines(todayOffset)
    val rangeProvider = rememberWeeklyVolumeRangeProvider(currentPoints, previousPoints)
    val markerVisibilityListener =
        rememberWeeklyVolumeMarkerVisibilityListener(onStateChanged = { selectedState = it })
    val tooltipData = rememberWeeklyVolumeTooltipData(daily, selectedState)

    WeeklyVolumeTrendChartContent(
        modifier = modifier,
        spec =
            WeeklyVolumeChartSpec(
                modelProducer = modelProducer,
                currentLine = currentLine,
                previousLine = previousLine,
                rangeProvider = rangeProvider,
                weekdayLabels = weekdayLabels,
                markerVisibilityListener = markerVisibilityListener,
            ),
        selectedState = selectedState,
        tooltipData = tooltipData,
        onDismissTooltip = { selectedState = null },
    )
}

/**
 * Bundles the Vico building blocks for the chart so [WeeklyVolumeTrendChartContent] stays within
 * detekt's parameter budget. All members are remembered upstream and never mutated.
 */
private data class WeeklyVolumeChartSpec(
    val modelProducer: CartesianChartModelProducer,
    val currentLine: LineCartesianLayer.Line,
    val previousLine: LineCartesianLayer.Line,
    val rangeProvider: CartesianLayerRangeProvider,
    val weekdayLabels: List<String>,
    val markerVisibilityListener: CartesianMarkerVisibilityListener,
)

@Composable
private fun rememberWeeklyVolumeModelProducer(
    currentPoints: List<DailyDataPoint>,
    previousPoints: List<DailyDataPoint>,
): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(currentPoints, previousPoints) {
        modelProducer.runTransaction {
            lineModel {
                weeklyVolumeSeriesOrder(currentPoints, previousPoints).forEach { points ->
                    series(x = points.map { it.dayOffset }, y = points.map { requireNotNull(it.value) })
                }
            }
        }
    }
    return modelProducer
}

@Composable
private fun rememberWeeklyVolumeLines(todayOffset: Int?): Pair<LineCartesianLayer.Line, LineCartesianLayer.Line> {
    val primaryColor = MaterialTheme.colorScheme.primary
    val todayDotComponent = rememberShapeComponent(fill = Fill(primaryColor), shape = CircleShape)
    val todayPointProvider =
        remember(todayOffset, todayDotComponent) {
            val point = LineCartesianLayer.Point(todayDotComponent, TODAY_POINT_SIZE_DP.dp)
            object : LineCartesianLayer.PointProvider {
                override fun getPoint(
                    entry: LineCartesianLayerModel.Entry,
                    extraStore: ExtraStore,
                ): LineCartesianLayer.Point? =
                    if (todayOffset != null && entry.x == todayOffset.toDouble()) point else null

                override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = point
            }
        }

    val currentLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(primaryColor)),
            areaFill =
                LineCartesianLayer.AreaFill.single(
                    Fill(
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(primaryColor.copy(alpha = 0.25f), primaryColor.copy(alpha = 0.0f)),
                            ),
                    ),
                ),
            pointProvider = todayPointProvider,
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )
    val previousLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(primaryColor.copy(alpha = PREVIOUS_WEEK_LINE_ALPHA))),
            stroke =
                LineCartesianLayer.LineStroke.Dashed(
                    dashLength = PREVIOUS_WEEK_DASH_LENGTH_DP.dp,
                    gapLength = PREVIOUS_WEEK_GAP_LENGTH_DP.dp,
                ),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )
    return currentLine to previousLine
}

@Composable
private fun rememberWeeklyVolumeRangeProvider(
    currentPoints: List<DailyDataPoint>,
    previousPoints: List<DailyDataPoint>,
): CartesianLayerRangeProvider {
    val maxY =
        remember(currentPoints, previousPoints) {
            val maxValue =
                maxOf(
                    currentPoints.maxOfOrNull { it.value ?: 0f } ?: 0f,
                    previousPoints.maxOfOrNull { it.value ?: 0f } ?: 0f,
                )
            (ceil(maxValue / Y_AXIS_STEP_MINUTES) * Y_AXIS_STEP_MINUTES).coerceAtLeast(Y_AXIS_FLOOR_MINUTES)
        }
    return remember(maxY) {
        CartesianLayerRangeProvider.fixed(
            minX = 0.0,
            maxX = (DAYS_IN_WEEK - 1).toDouble(),
            minY = 0.0,
            maxY = maxY,
        )
    }
}

@Composable
private fun rememberWeeklyVolumeTooltipData(
    daily: List<DailyTrainingVolume>,
    selectedState: WeeklyVolumeSelectedState?,
): DataPointTooltipData? {
    val thisWeekFormat = stringResource(R.string.weekly_volume_tooltip_this_week_format)
    val lastWeekFormat = stringResource(R.string.weekly_volume_tooltip_last_week_format)
    val diffFormat = stringResource(R.string.weekly_volume_tooltip_diff_format)
    val datePattern = stringResource(R.string.weekly_volume_tooltip_date_pattern)
    val locale = LocalLocale.current.platformLocale

    return remember(selectedState, daily, thisWeekFormat, lastWeekFormat, diffFormat, datePattern, locale) {
        selectedState?.let { state ->
            val date = daily.getOrNull(state.dayOffset)?.date
            val delta = WeeklyVolumeTrendMapper.dailyDelta(state.currentMinutes, state.previousMinutes)
            // The card leads with the current week, so "This week" is the first line under the date
            // and "Last week" follows it. On a day after today there is no current-week point.
            val thisWeekLine =
                state.currentMinutes?.let {
                    thisWeekFormat.format(WeeklyTrainingDeltaFormatter.formatDuration(it))
                }
            val lastWeekLine =
                lastWeekFormat.format(WeeklyTrainingDeltaFormatter.formatDuration(state.previousMinutes))
            DataPointTooltipData(
                valueText = date?.format(ChartUtils.getDateFormatter(datePattern, locale)).orEmpty(),
                dateText = thisWeekLine ?: lastWeekLine,
                preDateLines = if (thisWeekLine != null) listOf(lastWeekLine) else emptyList(),
                extraLine =
                    delta?.let {
                        diffFormat.format(
                            WeeklyTrainingDeltaFormatter.formatDurationDelta(it.deltaMinutes, it.percentChange),
                        )
                    },
                offset = IntOffset(state.canvasX.toInt(), (state.canvasY ?: 0f).toInt()),
            )
        }
    }
}

@Composable
private fun WeeklyVolumeTrendChartContent(
    spec: WeeklyVolumeChartSpec,
    selectedState: WeeklyVolumeSelectedState?,
    tooltipData: DataPointTooltipData?,
    onDismissTooltip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekdayLabels = spec.weekdayLabels
    val labelComponent = ChartDefaults.labelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val xAxisFormatter =
        remember(weekdayLabels) {
            CartesianValueFormatter { _, value, _ -> weekdayLabels.getOrElse(value.toInt()) { "" } }
        }
    val yAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> value.toInt().toString() } }
    val itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }, addExtremeLabelPadding = true) }

    Box(modifier = modifier.fillMaxWidth()) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider =
                            LineCartesianLayer.LineProvider.series(
                                weeklyVolumeSeriesOrder(spec.currentLine, spec.previousLine),
                            ),
                        rangeProvider = spec.rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            valueFormatter = yAxisFormatter,
                            guideline = guidelineComponent,
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            valueFormatter = xAxisFormatter,
                            itemPlacer = itemPlacer,
                            guideline = guidelineComponent,
                        ),
                    marker = InvisibleMarker,
                    markerVisibilityListener = spec.markerVisibilityListener,
                ),
            modelProducer = spec.modelProducer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp),
        )

        VicoChartTooltipOverlay(
            selectedPointOffset = selectedState?.let { Offset(it.canvasX, it.canvasY ?: 0f) },
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp),
        )

        tooltipData?.let { data ->
            DataPointTooltip(
                isVisible = true,
                data = data,
                onDismissRequest = onDismissTooltip,
            )
        }
    }
}

@Composable
private fun EmptyWeeklyVolumeTrendPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(CoreUiR.string.message_no_data_available),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val TODAY_POINT_SIZE_DP = 9
internal const val PREVIOUS_WEEK_LINE_ALPHA = 0.55f
internal const val PREVIOUS_WEEK_DASH_LENGTH_DP = 6
internal const val PREVIOUS_WEEK_GAP_LENGTH_DP = 4
