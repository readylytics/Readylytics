package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.deepSleepStatus
import com.gregor.lauritz.healthdashboard.domain.model.efficiencyStatus
import com.gregor.lauritz.healthdashboard.domain.model.remSleepStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.util.roundToPercentInt
import com.gregor.lauritz.healthdashboard.ui.common.MetricCardSkeleton
import com.gregor.lauritz.healthdashboard.ui.common.ScoreDialSkeleton
import com.gregor.lauritz.healthdashboard.ui.common.SkeletonCard
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.CircadianConsistencyCard
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.SleepArchitectureBar
import com.gregor.lauritz.healthdashboard.ui.components.StatusLegend
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.components.TrendChart
import com.gregor.lauritz.healthdashboard.ui.dashboard.DateSwitcher
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SleepRoute(
    onNavigateToDetail: (() -> Unit)? = null,
    viewModel: SleepViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val baselines by viewModel.baselinesFlow.collectAsStateWithLifecycle()
    val circadian by viewModel.circadianConsistencyFlow.collectAsStateWithLifecycle()

    SleepScreen(
        uiState = uiState,
        baselineHrv = baselines.hrv,
        baselineRhr = baselines.rhr,
        circadianConsistency = circadian,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onNavigateToDetail = onNavigateToDetail,
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
    onNavigateToDetail: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val chartScrollState = rememberVicoScrollState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item(key = "date_switcher") {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "score_dial") {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    ScoreDialSkeleton()
                } else {
                    val sleepScoreTooltip =
                        remember {
                            buildString {
                                append("Total quality of rest based on duration and cycles.\n\n")
                                append("• 80–100: Optimal\n")
                                append("• 60–79: Fair\n")
                                append("• < 60: Poor")
                            }
                        }
                    M3ScoreDial(
                        score = uiState.latestSummary?.sleepScore,
                        label = "Sleep Score",
                        tooltipDescription = sleepScoreTooltip,
                    )
                }
            }
        }

        item(key = "architecture_bar") {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    height = 120.dp,
                )
            } else {
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
                val bedTime =
                    uiState.latestSession?.let {
                        Instant
                            .ofEpochMilli(it.startTime)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
                    } ?: "—"
                val wakeTime =
                    uiState.latestSession?.let {
                        Instant
                            .ofEpochMilli(it.endTime)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
                    } ?: "—"
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable(enabled = onNavigateToDetail != null) {
                                onNavigateToDetail?.invoke()
                            },
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(sectionLabel, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "$bedTime – $wakeTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SleepArchitectureBar(
                            session = uiState.latestSession,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item(key = "spacer_arch") { Spacer(Modifier.height(24.dp)) }

        item(key = "trends_header") {
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

        item(key = "spacer_trends") { Spacer(Modifier.height(8.dp)) }

        item(key = "hrv_chart") {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    height = 250.dp,
                )
            } else {
                TrendCard(
                    title = "HRV",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailyHrv,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        baselineUnit = "ms",
                        baseline = baselineHrv,
                        showBaseline = !(uiState.latestSummary?.isCalibrating ?: false),
                        scrollState = chartScrollState,
                    )
                }
            }
        }

        item(key = "spacer_hrv") { Spacer(Modifier.height(8.dp)) }

        item(key = "rhr_chart") {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    height = 250.dp,
                )
            } else {
                TrendCard(
                    title = "Resting Heart Rate",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TrendChart(
                        points = uiState.dailyRhr,
                        rangeStartMs = uiState.rangeStartMs,
                        rangeDays = uiState.selectedRange.days,
                        baselineUnit = "bpm",
                        baseline = baselineRhr?.toFloat(),
                        showBaseline = !(uiState.latestSummary?.isCalibrating ?: false),
                        scrollState = chartScrollState,
                    )
                }
            }
        }

        item(key = "spacer_rhr") { Spacer(Modifier.height(24.dp)) }

        item(key = "metrics_header") {
            SectionHeader(title = "Metrics")
            Spacer(Modifier.height(8.dp))
        }

        item(key = "metrics_grid") {
            if (uiState.isLoading) {
                MetricsGridSkeleton()
            } else {
                MetricsGrid(
                    uiState = uiState,
                    circadianResult = circadianConsistency,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item(key = "spacer_bottom") { Spacer(Modifier.height(16.dp)) }

        item(key = "status_legend") {
            StatusLegend()
        }
    }
}

@Composable
private fun MetricsGrid(
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    modifier: Modifier = Modifier,
) {
    val session = uiState.latestSession
    val summary = uiState.latestSummary

    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.CALIBRATING
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.CALIBRATING
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.CALIBRATING

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                CircadianConsistencyCard(
                    result = circadianResult,
                    onClick = null,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCard(
                    title = "Sleep Efficiency",
                    value = session?.let { "${it.efficiency.roundToPercentInt()}%" } ?: "—",
                    secondaryText = "Goal: >85%",
                    status = efficiencyStatus,
                    tooltip = "The percentage of time actually asleep while in bed. (Goal: >85%).",
                    onClick = null,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCard(
                    title = "Deep Sleep",
                    value = summary?.deepSleepPercent?.let { "${it.roundToPercentInt()}%" } ?: "—",
                    secondaryText = "Target: 15–25%",
                    status = deepStatus,
                    tooltip = "Time in Stage 3 (Physical repair). Target: 15–25% of total sleep.",
                    onClick = null,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCard(
                    title = "REM Sleep",
                    value = summary?.remSleepPercent?.let { "${it.roundToPercentInt()}%" } ?: "—",
                    secondaryText = "Target: 20–25%",
                    status = remStatus,
                    tooltip = "Time in Rapid Eye Movement. Target: 20–25% of total sleep.",
                    onClick = null,
                )
            }
        }
    }
}

@Composable
private fun MetricsGridSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
    }
}

private data class MetricCardData(
    val title: String,
    val value: String,
    val status: MetricStatus,
    val tooltip: String,
)
