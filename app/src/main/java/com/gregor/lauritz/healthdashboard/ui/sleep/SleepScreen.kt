package com.gregor.lauritz.healthdashboard.ui.sleep

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.SleepArchitectureBar
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.component.LineComponent
import kotlin.math.roundToInt

@Composable
fun SleepRoute(viewModel: SleepViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SleepScreen(uiState = uiState, onRangeSelected = viewModel::onRangeSelected)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    uiState: SleepUiState,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                M3ScoreDial(score = uiState.latestSummary?.sleepScore, label = "Sleep Score")
            }
        }

        item {
            SectionHeader(title = "Last Night")
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
                TrendChart(data = uiState.dailyHrv)
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            TrendCard(
                title = "Resting Heart Rate",
                unit = "bpm",
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                TrendChart(data = uiState.dailyRhr)
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
    data: List<Float>,
    modifier: Modifier = Modifier,
) {
    if (data.isEmpty()) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val baseline =
        remember(data) {
            val sorted = data.sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 0) {
                (sorted[mid - 1] + sorted[mid]) / 2f
            } else {
                sorted[mid]
            }
        }

    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(data) {
        modelProducer.runTransaction {
            lineSeries { series(data) }
        }
    }

    CartesianChartHost(
        chart =
            rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(),
                decorations =
                    listOf(
                        HorizontalLine(
                            y = { baseline.toDouble() },
                            line = LineComponent(fill = Fill(baselineColor.toArgb()), thicknessDp = 1f),
                        ),
                    ),
            ),
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth().height(180.dp),
    )
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
                tooltip =
                    "The percentage of time actually asleep while in bed. (Goal: >85%).",
            ),
            MetricCardData(
                title = "Deep Sleep",
                value = summary?.deepSleepPercent?.let { "${it.toInt()}%" } ?: "—",
                status = deepStatus,
                tooltip =
                    "Time in Slow Wave Sleep; responsible for tissue repair and growth hormone release.",
            ),
            MetricCardData(
                title = "REM Sleep",
                value = summary?.remSleepPercent?.let { "${it.toInt()}%" } ?: "—",
                status = remStatus,
                tooltip =
                    "Time in Rapid Eye Movement sleep; vital for memory and emotional processing.",
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
                MetricTooltip(description = tooltipText)
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
