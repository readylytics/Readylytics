package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.SleepArchitectureBar
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import com.gregor.lauritz.healthdashboard.ui.dashboard.DateSwitcher
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
fun SleepRoute(viewModel: SleepViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val baselineHrv by viewModel.baselineHrvFlow.collectAsStateWithLifecycle()
    val baselineRhr by viewModel.baselineRhrFlow.collectAsStateWithLifecycle()
    SleepScreen(
        uiState = uiState,
        baselineHrv = baselineHrv,
        baselineRhr = baselineRhr,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    uiState: SleepUiState,
    baselineHrv: Float?,
    baselineRhr: Int?,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chartScrollState = rememberVicoScrollState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            DateSwitcher(
                selectedDate = uiState.selectedDate,
                onPreviousDay = onPreviousDay,
                onNextDay = onNextDay,
            )
        }

        item {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                M3ScoreDial(
                    score = uiState.latestSummary?.sleepScore,
                    label = "Sleep Score",
                    tooltipDescription =
                        buildString {
                            append("Total quality of rest based on duration and cycles.\n\n")
                            append("• 80–100: Optimal\n")
                            append("• 60–79: Fair\n")
                            append("• < 60: Poor")
                        },
                )
            }
        }

        item {
            val sectionLabel =
                remember(uiState.selectedDate) {
                    val today = java.time.LocalDate.now()
                    when (uiState.selectedDate) {
                        today -> "Last Night"
                        today.minusDays(1) -> "Night of Yesterday"
                        else -> {
                            val pattern = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.getDefault())
                            "Night of ${uiState.selectedDate.format(pattern)}"
                        }
                    }
                }
            SectionHeader(title = sectionLabel)
            Spacer(Modifier.height(8.dp))
            SleepArchitectureBar(
                session = uiState.latestSession,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
            SectionHeader(title = "Restoration Trends")
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                TimeRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = uiState.selectedRange == range,
                        onClick = { onRangeSelected(range) },
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = TimeRange.entries.size,
                            ),
                        label = { Text(range.label) },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            TrendCard(
                title = "HRV",
                unit = "ms",
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                TrendChart(
                    points = uiState.dailyHrv,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = uiState.selectedRange.days,
                    baselineUnit = "ms",
                    baseline = baselineHrv,
                    scrollState = chartScrollState,
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            TrendCard(
                title = "Resting Heart Rate",
                unit = "bpm",
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                TrendChart(
                    points = uiState.dailyRhr,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = uiState.selectedRange.days,
                    baselineUnit = "bpm",
                    baseline = baselineRhr?.toFloat(),
                    scrollState = chartScrollState,
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
            SectionHeader(title = "Metrics")
            Spacer(Modifier.height(8.dp))
            SleepMetricGrid(
                session = uiState.latestSession,
                summary = uiState.latestSummary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun TrendCard(
    title: String,
    unit: String,
    modifier: Modifier = Modifier,
    chart: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            chart()
        }
    }
}

@Composable
private fun TrendChart(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    baselineUnit: String,
    baseline: Float? = null,
    scrollState: VicoScrollState = rememberVicoScrollState(),
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val dayMs = TimeUnit.DAYS.toMillis(1)
    val calculatedBaseline =
        remember(points) {
            val sorted = points.map { it.value }.sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }

    // Use provided baseline if available, otherwise fall back to calculated baseline
    val baselineValue = baseline ?: calculatedBaseline

    val minY =
        remember(points) {
            (points.minOf { it.value } * 0.9f).toDouble()
        }
    val maxY =
        remember(points) {
            (points.maxOf { it.value } * 1.1f).toDouble()
        }

    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelComponent = rememberTextComponent(color = labelColor)
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guidelineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val dotColor = MaterialTheme.colorScheme.primary

    val modelProducer = remember { CartesianChartModelProducer() }

    val dateFormatter =
        remember(rangeStartMs) {
            SimpleDateFormat(DateFormatUtils.DATE_FORMAT_SHORT, Locale.getDefault())
        }
    val xAxisFormatter =
        remember(rangeStartMs) {
            CartesianValueFormatter { _, value, _ ->
                dateFormatter.format(Date(rangeStartMs + value.toLong() * dayMs))
            }
        }

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = points.map { it.dayOffset },
                    y = points.map { it.value },
                )
            }
        }
    }

    val rangeProvider = remember(minY, maxY) { CartesianLayerRangeProvider.fixed(minY = minY, maxY = maxY) }
    val dotComponent = rememberShapeComponent(fill = fill(dotColor), shape = CorneredShape.Pill)
    val line =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(fill(dotColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(dotComponent, 6.dp),
                ),
        )

    CartesianChartHost(
        chart =
            rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(line),
                    rangeProvider = rangeProvider,
                ),
                startAxis =
                    VerticalAxis.rememberStart(
                        label = labelComponent,
                        valueFormatter = CartesianValueFormatter { _, value, _ -> value.toInt().toString() },
                        guideline = LineComponent(fill = fill(guidelineColor), thicknessDp = 1f),
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = labelComponent,
                        valueFormatter = xAxisFormatter,
                        itemPlacer =
                            remember(rangeDays) {
                                if (rangeDays == 7) {
                                    HorizontalAxis.ItemPlacer.aligned(
                                        spacing = { 2 },
                                        addExtremeLabelPadding = true,
                                    )
                                } else {
                                    HorizontalAxis.ItemPlacer.aligned(
                                        spacing = { 5 },
                                        addExtremeLabelPadding = true,
                                    )
                                }
                            },
                        guideline = LineComponent(fill = fill(guidelineColor), thicknessDp = 1f),
                    ),
                decorations =
                    listOf(
                        HorizontalLine(
                            y = { baselineValue.toDouble() },
                            line = LineComponent(fill = fill(baselineColor), thicknessDp = 1f),
                        ),
                    ),
            ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        zoomState = rememberVicoZoomState(zoomEnabled = false),
        modifier = modifier.fillMaxWidth().height(180.dp),
    )

    Spacer(Modifier.height(6.dp))
    BaselineLegend(
        value = baselineValue,
        unit = baselineUnit,
        color = baselineColor,
    )
}

@Composable
private fun BaselineLegend(
    value: Float,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(16.dp)
                    .height(2.dp)
                    .background(color),
        )
        Text(
            text = "Baseline: ${value.roundToInt()} $unit",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun EmptyChartPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Not enough data",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepMetricGrid(
    session: SleepSessionEntity?,
    summary: DailySummaryEntity?,
    modifier: Modifier = Modifier,
) {
    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.CALIBRATING
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.CALIBRATING
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.CALIBRATING

    val cards =
        listOf(
            MetricCardData(
                title = "Sleep Efficiency",
                value = session?.let { "${it.efficiency.roundToInt()}%" } ?: "—",
                status = efficiencyStatus,
                tooltip = "The percentage of time actually asleep while in bed. (Goal: >85%).",
            ),
            MetricCardData(
                title = "Deep Sleep",
                value = summary?.deepSleepPercent?.let { "${it.toInt()}%" } ?: "—",
                status = deepStatus,
                tooltip = "Time in Stage 3 (Physical repair). Target: 15–25% of total sleep.",
            ),
            MetricCardData(
                title = "REM Sleep",
                value = summary?.remSleepPercent?.let { "${it.toInt()}%" } ?: "—",
                status = remStatus,
                tooltip = "Time in Rapid Eye Movement. Target: 20–25% of total sleep.",
            ),
        )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { card ->
                    SleepMetricCard(
                        title = card.title,
                        value = card.value,
                        status = card.status,
                        tooltipText = card.tooltip,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SleepMetricCard(
    title: String,
    value: String,
    status: MetricStatus,
    tooltipText: String,
    modifier: Modifier = Modifier,
) {
    val containerColor = status.containerColor()
    val contentColor = status.onContainerColor()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
                MetricTooltip(description = tooltipText, iconTint = contentColor)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = contentColor,
            )
        }
    }
}

private data class MetricCardData(
    val title: String,
    val value: String,
    val status: MetricStatus,
    val tooltip: String,
)
