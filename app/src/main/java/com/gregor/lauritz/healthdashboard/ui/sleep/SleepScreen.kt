package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.scoring.toStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.toTimeString
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.SleepArchitectureBar
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import com.gregor.lauritz.healthdashboard.ui.dashboard.DateSwitcher
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SleepRoute(
    viewModel: SleepViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bHrv by viewModel.baselineHrvFlow.collectAsStateWithLifecycle()
    val bRhr by viewModel.baselineRhrFlow.collectAsStateWithLifecycle()
    val circadian by viewModel.circadianConsistencyFlow.collectAsStateWithLifecycle()

    SleepScreen(
        uiState = uiState,
        baselineHrv = bHrv,
        baselineRhr = bRhr,
        circadianConsistency = circadian,
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
    circadianConsistency: CircadianConsistencyResult,
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
                            val pattern = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())
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
                circadianResult = circadianConsistency,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SleepMetricGrid(
    session: SleepSessionEntity?,
    summary: DailySummaryEntity?,
    circadianResult: CircadianConsistencyResult,
    modifier: Modifier = Modifier,
) {
    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.CALIBRATING
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.CALIBRATING
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.CALIBRATING

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircadianConsistencyCard(
                result = circadianResult,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            MetricCard(
                title = "Sleep Efficiency",
                value = session?.let { "${it.efficiency.roundToInt()}%" } ?: "—",
                secondaryText = "Goal: >85%",
                status = efficiencyStatus,
                tooltip ="The percentage of time actually asleep while in bed. (Goal: >85%).",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "Deep Sleep",
                value = summary?.deepSleepPercent?.let { "${it.toInt()}%" } ?: "—",
                secondaryText = "Target: 15–25%",
                status = deepStatus,
                tooltip ="Time in Stage 3 (Physical repair). Target: 15–25% of total sleep.",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MetricCard(
                title = "REM Sleep",
                value = summary?.remSleepPercent?.let { "${it.toInt()}%" } ?: "—",
                secondaryText = "Target: 20–25%",
                status = remStatus,
                tooltip ="Time in Rapid Eye Movement. Target: 20–25% of total sleep.",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun CircadianConsistencyCard(
    result: CircadianConsistencyResult,
    modifier: Modifier = Modifier,
) {
    val status = result.toStatus()
    val containerColor = status.containerColor()
    val contentColor = status.onContainerColor()

    val scoreText = when (result) {
        is CircadianConsistencyResult.Calibrating -> "Calibrating"
        is CircadianConsistencyResult.Ready -> "${result.score.toInt()}%"
    }
    val windowText = when (result) {
        is CircadianConsistencyResult.Calibrating -> null
        is CircadianConsistencyResult.Ready ->
            "${result.medianBedtimeMinutes.toTimeString()}→${result.medianWakeMinutes.toTimeString()}"
    }
    val thresholdMinutes = when (result) {
        is CircadianConsistencyResult.Calibrating -> 30
        is CircadianConsistencyResult.Ready -> result.thresholdMinutes
    }

    val tooltipText =
        buildString {
            append("Measures how regular your sleep schedule is.\n\n")
            append("High consistency stabilizes your internal clock, improving deep sleep and energy levels.\n\n")
            append("• ≥ 80%: Optimal\n")
            append("• 60–79%: Neutral\n")
            append("• 40–59%: Warning\n")
            append("• < 40%: Poor\n\n")
            append("Consistency Window: ±$thresholdMinutes min grace period before score drops.")
        }

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
                    text = "Circadian",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
                MetricTooltip(description = tooltipText, iconTint = contentColor)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = scoreText,
                style = MaterialTheme.typography.displaySmall,
                color = contentColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = windowText ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
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
