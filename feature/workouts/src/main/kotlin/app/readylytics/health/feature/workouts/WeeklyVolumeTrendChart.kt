package app.readylytics.health.feature.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.scoring.domain.workouts.weekly.DailyTrainingVolume
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
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
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import app.readylytics.health.core.ui.R as CoreUiR

private const val DAYS_IN_WEEK = 7
private const val CHART_HEIGHT_DP = 200
private const val Y_AXIS_STEP_MINUTES = 30.0
private const val Y_AXIS_FLOOR_MINUTES = 60.0
private val TOOLTIP_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

@Composable
fun WeeklyVolumeTrendChartCard(
    stats: WeeklyTrainingStats?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isLoading || stats == null) {
        SkeletonCard(height = 280.dp, modifier = modifier.fillMaxWidth())
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
            WeeklyVolumeHeadline(stats)
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            val daily = stats.cumulativeDailyTraining
            val hasAnyData =
                daily.any { (it.currentWeekDurationMinutes ?: 0) > 0 || it.previousWeekDurationMinutes > 0 }
            if (hasAnyData) {
                WeeklyVolumeTrendChart(daily = daily)
            } else {
                EmptyWeeklyVolumeTrendPlaceholder()
            }
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            WeeklyVolumeTrendLegend()
        }
    }
}

@Composable
private fun WeeklyVolumeHeadline(stats: WeeklyTrainingStats) {
    val delta =
        weeklyDeltaDisplay(
            current = stats.currentWeek.totalDurationMinutes,
            previous = stats.previousWeek.totalDurationMinutes,
            detail =
                WeeklyTrainingDeltaFormatter.formatDurationDelta(
                    stats.comparison.durationDeltaMinutes,
                    stats.comparison.durationPercentChange,
                ),
        )
    Column {
        Text(
            text = WeeklyTrainingDeltaFormatter.formatDuration(stats.currentWeek.totalDurationMinutes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "${delta.text} ${stringResource(R.string.workout_stats_weekly_vs_last_week)}",
            style = MaterialTheme.typography.labelMedium,
            color = delta.color,
        )
    }
}

@Composable
private fun WeeklyVolumeTrendChart(
    daily: List<DailyTrainingVolume>,
    modifier: Modifier = Modifier,
) {
    var selectedState by remember(daily) { mutableStateOf<WeeklyVolumeSelectedState?>(null) }

    val series = remember(daily) { WeeklyVolumeTrendMapper.toSeries(daily) }
    val currentPoints = series.first
    val previousPoints = series.second
    val todayOffset = remember(daily) { WeeklyVolumeTrendMapper.todayOffset(daily) }
    val weekdayLabels =
        remember(daily) {
            daily.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }

    val modelProducer = rememberWeeklyVolumeModelProducer(currentPoints, previousPoints)
    val (currentLine, previousLine) = rememberWeeklyVolumeLines(todayOffset)
    val rangeProvider = rememberWeeklyVolumeRangeProvider(currentPoints, previousPoints)
    val markerVisibilityListener =
        rememberWeeklyVolumeMarkerVisibilityListener(onStateChanged = { selectedState = it })
    val tooltipData = rememberWeeklyVolumeTooltipData(daily, selectedState)

    WeeklyVolumeTrendChartContent(
        modifier = modifier,
        modelProducer = modelProducer,
        currentLine = currentLine,
        previousLine = previousLine,
        rangeProvider = rangeProvider,
        weekdayLabels = weekdayLabels,
        markerVisibilityListener = markerVisibilityListener,
        selectedState = selectedState,
        tooltipData = tooltipData,
        onDismissTooltip = { selectedState = null },
    )
}

@Composable
private fun rememberWeeklyVolumeModelProducer(
    currentPoints: List<DailyDataPoint>,
    previousPoints: List<DailyDataPoint>,
): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(currentPoints, previousPoints) {
        modelProducer.runTransaction {
            lineModel {
                series(x = currentPoints.map { it.dayOffset }, y = currentPoints.map { requireNotNull(it.value) })
                series(x = previousPoints.map { it.dayOffset }, y = previousPoints.map { requireNotNull(it.value) })
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

    return remember(selectedState, daily, thisWeekFormat, lastWeekFormat, diffFormat) {
        selectedState?.let { state ->
            val date = daily.getOrNull(state.dayOffset)?.date
            val delta = WeeklyVolumeTrendMapper.dailyDelta(state.currentMinutes, state.previousMinutes)
            DataPointTooltipData(
                valueText = date?.format(TOOLTIP_DATE_FORMAT).orEmpty(),
                dateText = lastWeekFormat.format(WeeklyTrainingDeltaFormatter.formatDuration(state.previousMinutes)),
                preDateLines =
                    state.currentMinutes
                        ?.let {
                            listOf(thisWeekFormat.format(WeeklyTrainingDeltaFormatter.formatDuration(it)))
                        }.orEmpty(),
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

@Suppress("LongParameterList")
@Composable
private fun WeeklyVolumeTrendChartContent(
    modelProducer: CartesianChartModelProducer,
    currentLine: LineCartesianLayer.Line,
    previousLine: LineCartesianLayer.Line,
    rangeProvider: CartesianLayerRangeProvider,
    weekdayLabels: List<String>,
    markerVisibilityListener: CartesianMarkerVisibilityListener,
    selectedState: WeeklyVolumeSelectedState?,
    tooltipData: DataPointTooltipData?,
    onDismissTooltip: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                        lineProvider = LineCartesianLayer.LineProvider.series(currentLine, previousLine),
                        rangeProvider = rangeProvider,
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
                    markerVisibilityListener = markerVisibilityListener,
                ),
            modelProducer = modelProducer,
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

@Composable
private fun WeeklyVolumeTrendLegend(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(width = 16.dp, height = 2.dp).background(primaryColor))
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.weekly_volume_trend_legend_this_week),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 16.dp, height = 2.dp)
                        .background(primaryColor.copy(alpha = PREVIOUS_WEEK_LINE_ALPHA)),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.weekly_volume_trend_legend_last_week),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val TODAY_POINT_SIZE_DP = 9
private const val PREVIOUS_WEEK_LINE_ALPHA = 0.55f
private const val PREVIOUS_WEEK_DASH_LENGTH_DP = 6
private const val PREVIOUS_WEEK_GAP_LENGTH_DP = 4
