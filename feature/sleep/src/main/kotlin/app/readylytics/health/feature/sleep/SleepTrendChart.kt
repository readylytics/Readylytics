package app.readylytics.health.feature.sleep

import android.text.format.DateFormat
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepTrendDay
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.components.AutoDismissChartSelection
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.InvisibleMarker
import app.readylytics.health.core.ui.components.rememberCountItemPlacer
import app.readylytics.health.core.ui.components.rememberCubicLineCartesianLayer
import app.readylytics.health.core.ui.components.rememberLongPressMarkerController
import app.readylytics.health.core.ui.components.rememberOrdinalRemappedPoints
import app.readylytics.health.core.ui.components.rememberPeriodAxisFormatter
import app.readylytics.health.core.ui.components.rememberPeriodLabels
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun SleepTrendCard(
    selectedRange: TimeRange,
    startOffsetPoints: List<DailyDataPoint>,
    durationSpanPoints: List<DailyDataPoint>,
    actualDurationPoints: List<DailyDataPoint>,
    trendDays: List<SleepTrendDay>,
    rangeStartMs: Long,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
    scoringZoneId: ZoneId = ZoneId.systemDefault(),
    actualDurationSummary: PeriodAverageSummary? = null,
) {
    Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = stringResource(R.string.sleep_trend_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            SleepTrendChart(
                selectedRange = selectedRange,
                startOffsetPoints = startOffsetPoints,
                durationSpanPoints = durationSpanPoints,
                actualDurationPoints = actualDurationPoints,
                trendDays = trendDays,
                rangeStartMs = rangeStartMs,
                scrollState = scrollState,
                zoomState = zoomState,
                parentScrollInProgress = parentScrollInProgress,
                scoringZoneId = scoringZoneId,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            SleepTrendChartLegends(
                barColor = MaterialTheme.colorScheme.primary,
                lineColor = MaterialTheme.colorScheme.tertiary,
            )
            actualDurationSummary?.let {
                Spacer(Modifier.height(MaterialTheme.spacing.small))
                SleepTrendSummaryRow(it)
            }
        }
    }
}

@Composable
fun SleepTrendChart(
    selectedRange: TimeRange,
    startOffsetPoints: List<DailyDataPoint>,
    durationSpanPoints: List<DailyDataPoint>,
    actualDurationPoints: List<DailyDataPoint>,
    trendDays: List<SleepTrendDay>,
    rangeStartMs: Long,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
    scoringZoneId: ZoneId = ZoneId.systemDefault(),
) {
    val rangeDays = selectedRange.days
    var selectedState by remember(
        startOffsetPoints,
        durationSpanPoints,
        actualDurationPoints,
        trendDays,
        rangeStartMs,
    ) {
        mutableStateOf<SleepTrendSelectedState?>(null)
    }

    AutoDismissChartSelection(scrollState, parentScrollInProgress) { selectedState = null }

    val tooltipStrings =
        SleepTrendTooltipStrings(
            durationFormat = stringResource(R.string.sleep_trend_tooltip_duration_format),
            bedtimeFormat = stringResource(R.string.sleep_trend_tooltip_bedtime_format),
            napsHeading = stringResource(R.string.sleep_trend_tooltip_naps_heading),
            napItemFormat = stringResource(R.string.sleep_trend_tooltip_nap_item_format),
            avgDurationFormat = stringResource(R.string.sleep_trend_tooltip_avg_duration_format),
            avgBedtimeFormat = stringResource(R.string.sleep_trend_tooltip_avg_bedtime_format),
            quarterLabelFormat = stringResource(R.string.sleep_trend_tooltip_quarter_format),
        )
    val hoursOnlyFormat = stringResource(CoreUiR.string.sleep_duration_hours_only)
    val context = LocalContext.current
    val clockFormatter = remember(context) { DateFormat.getTimeFormat(context) }
    val granularity = selectedRange.granularity

    val periodLabels = rememberPeriodLabels(startOffsetPoints, rangeStartMs, granularity, scoringZoneId)
    val tooltipState =
        remember(
            selectedState,
            rangeStartMs,
            scoringZoneId,
            tooltipStrings,
            clockFormatter,
            granularity,
            periodLabels,
        ) {
            selectedState?.let { state ->
                buildSleepTrendTooltipData(
                    selectedState = state,
                    rangeStartMs = rangeStartMs,
                    scoringZoneId = scoringZoneId,
                    clockFormatter = clockFormatter,
                    strings = tooltipStrings,
                    granularity = granularity,
                    periodLabels = periodLabels,
                )
            }
        }

    val labelComponent = ChartDefaults.labelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    val remappedStartPoints = rememberOrdinalRemappedPoints(startOffsetPoints, granularity)
    val remappedSpanPoints = rememberOrdinalRemappedPoints(durationSpanPoints, granularity)
    val remappedActualPoints = rememberOrdinalRemappedPoints(actualDurationPoints, granularity)
    val xAxisRangeDays =
        remember(startOffsetPoints, granularity) {
            if (granularity == TrendGranularity.DAILY) rangeDays else startOffsetPoints.size
        }

    val leftAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> formatSleepTrendHour(value) } }
    val rightAxisFormatter =
        remember(hoursOnlyFormat) {
            CartesianValueFormatter { _, v, _ -> String.format(Locale.getDefault(), hoursOnlyFormat, v.roundToInt()) }
        }

    val modelProducer = rememberSleepTrendModelProducer(remappedStartPoints, remappedSpanPoints, remappedActualPoints)
    val rangeProviders =
        rememberSleepTrendRangeProviders(
            remappedStartPoints = remappedStartPoints,
            remappedSpanPoints = remappedSpanPoints,
            remappedActualPoints = remappedActualPoints,
            xAxisRangeDays = xAxisRangeDays,
        )
    val xAxisFormatter = rememberPeriodAxisFormatter(periodLabels, rangeStartMs, granularity, scoringZoneId)

    val hasData =
        remember(remappedStartPoints, remappedSpanPoints, remappedActualPoints) {
            remappedStartPoints.any { it.value != null } ||
                remappedSpanPoints.any { it.value != null } ||
                remappedActualPoints.any { it.value != null }
        }

    val transparentBar = rememberLineComponent(fill = Fill(Color.Transparent), thickness = 8.dp)
    val primaryBar =
        rememberLineComponent(
            fill = Fill(MaterialTheme.colorScheme.primary),
            thickness = 8.dp,
            shape = CircleShape,
        )
    val columnLayer =
        rememberColumnCartesianLayer(
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(transparentBar, primaryBar),
            mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
            rangeProvider = rangeProviders.leftRangeProvider,
            verticalAxisPosition = Axis.Position.Vertical.Start,
        )

    val lineLayer =
        rememberCubicLineCartesianLayer(
            lineColor = MaterialTheme.colorScheme.tertiary,
            rangeProvider = rangeProviders.rightRangeProvider,
        )

    val markerVisibilityListener =
        rememberSleepTrendMarkerVisibilityListener(
            startOffsetPoints = remappedStartPoints,
            durationSpanPoints = remappedSpanPoints,
            actualDurationPoints = remappedActualPoints,
            trendDays = trendDays,
            granularity = granularity,
            onStateChanged = { selectedState = it },
        )
    val markerController = rememberLongPressMarkerController()

    val chartHeight = 220.dp
    if (!hasData) {
        Box(
            modifier = modifier.fillMaxWidth().height(chartHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(CoreUiR.string.message_no_data_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Box(modifier = modifier.fillMaxWidth().testTag("SleepTrendChart")) {
            val bottomItemPlacer =
                ChartDefaults.rememberTrendAxisItemPlacer(
                    rangeDays = xAxisRangeDays,
                    granularity = granularity,
                    rangeStartMs = rangeStartMs,
                    explicitPointOffsets =
                        if (granularity == TrendGranularity.DAILY) {
                            emptyList()
                        } else {
                            remappedStartPoints.map { it.dayOffset }.distinct().sorted()
                        },
                )
            val startAxis =
                VerticalAxis.rememberStart(
                    label = labelComponent,
                    valueFormatter = leftAxisFormatter,
                    guideline = guidelineComponent,
                    itemPlacer = rememberCountItemPlacer(5),
                )
            val bottomAxis =
                HorizontalAxis.rememberBottom(
                    label = labelComponent,
                    valueFormatter = xAxisFormatter,
                    itemPlacer = bottomItemPlacer,
                    guideline = guidelineComponent,
                )
            val endAxis =
                VerticalAxis.rememberEnd(
                    label = labelComponent,
                    valueFormatter = rightAxisFormatter,
                    itemPlacer = rememberCountItemPlacer(5),
                )
            CartesianChartHost(
                chart =
                    rememberCartesianChart(
                        columnLayer,
                        lineLayer,
                        startAxis = startAxis,
                        bottomAxis = bottomAxis,
                        endAxis = endAxis,
                        marker = InvisibleMarker,
                        markerVisibilityListener = markerVisibilityListener,
                        markerController = markerController,
                    ),
                modelProducer = modelProducer,
                scrollState = scrollState,
                zoomState = zoomState,
                modifier = Modifier.fillMaxWidth().height(chartHeight).then(markerController.scrubGate),
            )

            SleepTrendOverlay(
                selectedState = selectedState,
                barColor = MaterialTheme.colorScheme.primary,
                lineColor = MaterialTheme.colorScheme.tertiary,
                barThicknessDp = 8.dp,
                chartHeight = chartHeight,
            )

            if (tooltipState != null) {
                DataPointTooltip(
                    isVisible = true,
                    data = tooltipState,
                    onDismissRequest = { selectedState = null },
                )
            }
        }
    }
}

@Composable
fun SleepTrendChartLegends(
    barColor: Color,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val barShape = MaterialTheme.shapes.extraSmall
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp, 8.dp).background(barColor, barShape))
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.sleep_trend_legend_window),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp, 2.dp).background(lineColor))
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.sleep_trend_legend_duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SleepTrendSkeleton(modifier: Modifier = Modifier) {
    SkeletonCard(height = 250.dp, modifier = modifier.fillMaxWidth())
}
