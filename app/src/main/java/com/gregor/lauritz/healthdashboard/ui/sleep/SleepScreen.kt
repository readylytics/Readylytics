package com.gregor.lauritz.healthdashboard.ui.sleep

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.R
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
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.CircadianConsistencyCard
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.SleepArchitectureBar
import com.gregor.lauritz.healthdashboard.ui.components.SleepStagesChart
import com.gregor.lauritz.healthdashboard.ui.components.StatusLegend
import com.gregor.lauritz.healthdashboard.ui.components.TrendCard
import com.gregor.lauritz.healthdashboard.ui.dashboard.DateSwitcher

@Composable
fun SleepRoute(viewModel: SleepViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val circadian by viewModel.circadianConsistencyFlow.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()

    SleepScreen(
        uiState = uiState,
        circadianConsistency = circadian,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = viewModel::onDateSelected,
        onTrendRangeSelected = viewModel::onTrendRangeSelected,
        earliestDate = earliestDate,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    uiState: SleepUiState,
    circadianConsistency: CircadianConsistencyResult,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    onTrendRangeSelected: (TimeRange) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val (trendScrollState, trendZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedTrendRange.days,
            key = uiState.selectedTrendRange,
        )

    LazyColumn(
        state = listState,
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
                    onDateSelected = onDateSelected,
                    earliestDate = earliestDate,
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
                        displayText = uiState.latestMetrics?.sleepScoreRounded?.toString() ?: "—",
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
                TrendCard(
                    title = stringResource(R.string.sleep_breakdown_title),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    SleepArchitectureBar(
                        session = uiState.latestSession,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "spacer_arch2") { Spacer(Modifier.height(8.dp)) }

        item(key = "hypnogram") {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    height = 260.dp,
                )
            } else {
                TrendCard(
                    title = stringResource(R.string.sleep_timeline_title),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    SleepStagesChart(
                        session = uiState.latestSession,
                        stageTimeline = uiState.stageTimeline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "spacer_trend") { Spacer(Modifier.height(16.dp)) }

        item(key = "sleep_trend_header") {
            SectionHeader(
                title = stringResource(R.string.sleep_trend_section_title),
                enabled = !uiState.isLoading,
            )
            Spacer(Modifier.height(8.dp))
        }

        item(key = "sleep_trend_range") {
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                TimeRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = uiState.selectedTrendRange == range,
                        onClick = { onTrendRangeSelected(range) },
                        enabled = !uiState.isLoading,
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = TimeRange.entries.size,
                            ),
                        label = { Text(range.label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        item(key = "sleep_trend_card") {
            if (uiState.isLoading) {
                SleepTrendSkeleton(modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                SleepTrendCard(
                    selectedRange = uiState.selectedTrendRange,
                    startOffsetPoints = uiState.trendStartOffsetPoints,
                    durationSpanPoints = uiState.trendDurationSpanPoints,
                    actualDurationPoints = uiState.trendActualDurationPoints,
                    rangeStartMs = uiState.trendRangeStartMs,
                    scrollState = trendScrollState,
                    zoomState = trendZoomState,
                    parentScrollInProgress = listState.isScrollInProgress,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item(key = "spacer_arch") { Spacer(Modifier.height(24.dp)) }

        item(key = "metrics_header") {
            SectionHeader(title = stringResource(R.string.sleep_metrics_title))
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
    val metrics = uiState.latestMetrics

    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.NO_DATA
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.NO_DATA
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.NO_DATA

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
                    title = stringResource(R.string.card_title_sleep_efficiency),
                    value =
                        session?.let {
                            stringResource(
                                R.string.card_efficiency_format,
                                it.efficiency.roundToPercentInt(),
                            )
                        }
                            ?: "—",
                    secondaryText = stringResource(R.string.card_goal_sleep_efficiency),
                    status = efficiencyStatus,
                    tooltip = stringResource(R.string.tooltip_sleep_efficiency),
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
                    title = stringResource(R.string.card_title_deep_sleep),
                    value = metrics?.deepSleepPercentDisplay ?: "—",
                    secondaryText = stringResource(R.string.card_target_deep_sleep),
                    status = deepStatus,
                    tooltip = stringResource(R.string.tooltip_deep_sleep),
                    onClick = null,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCard(
                    title = stringResource(R.string.card_title_rem_sleep),
                    value = metrics?.remSleepPercentDisplay ?: "—",
                    secondaryText = stringResource(R.string.card_target_rem_sleep),
                    status = remStatus,
                    tooltip = stringResource(R.string.tooltip_rem_sleep),
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
