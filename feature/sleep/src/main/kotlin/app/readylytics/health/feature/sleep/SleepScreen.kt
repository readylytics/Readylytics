package app.readylytics.health.feature.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.common.MetricCardSkeleton
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.CircadianConsistencyCard
import app.readylytics.health.core.ui.components.M3ScoreGaugeCard
import app.readylytics.health.core.ui.components.MetricCard
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.deepSleepStatus
import app.readylytics.health.domain.model.efficiencyStatus
import app.readylytics.health.domain.model.remSleepStatus
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.toStatus
import app.readylytics.health.domain.scoring.toTimeString
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.sleep.R

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
    modifier: Modifier = Modifier,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    onTrendRangeSelected: (TimeRange) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
) {
    val scrollState = rememberScrollState()
    val singleSessionVisual =
        remember(uiState.latestSession, uiState.latestSummary) {
            resolveSingleSessionVisual(uiState.latestSession, uiState.latestSummary)
        }
    val (trendScrollState, trendZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedTrendRange.days,
            key = uiState.selectedTrendRange,
        )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = MaterialTheme.spacing.pageTop, bottom = MaterialTheme.spacing.pageBottom),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
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

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MaterialTheme.spacing.pageHorizontal,
                        end = MaterialTheme.spacing.pageHorizontal,
                        top = MaterialTheme.spacing.pageSectionGap,
                        bottom = MaterialTheme.spacing.pageSectionGapSmall,
                    ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (uiState.isLoading) {
                ScoreDialSkeleton(
                    modifier = Modifier.weight(1f),
                )
                ScoreDialSkeleton(
                    modifier = Modifier.weight(1f),
                )
            } else {
                M3ScoreGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.sleep_score_gauge_title),
                    score = uiState.latestSummary?.sleepScore,
                    displayText = uiState.latestMetrics?.sleepScoreRounded?.toString() ?: "—",
                    unitText = "",
                    deltaText =
                        formatRoundedScoreDelta(
                            currentRounded = uiState.latestMetrics?.sleepScoreRounded,
                            previousRounded = uiState.yesterdaySleepScoreRounded,
                        ).resolveOrNull(),
                    tooltipDescription = stringResource(app.readylytics.health.core.ui.R.string.tooltip_sleep_score),
                )

                val sleepTimeGaugeData = uiState.sleepTimeGaugeData
                val goalText =
                    DateFormatUtils.formatSleepDuration(
                        (uiState.goalSleepHours * 60f).toInt().coerceAtLeast(0),
                    )

                M3ScoreGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.sleep_time_gauge_title),
                    score = sleepTimeGaugeData.progress,
                    displayText = sleepTimeGaugeData.displayText,
                    unitText = "",
                    maxScore = 1f,
                    status = sleepTimeGaugeData.status,
                    deltaText = sleepTimeGaugeData.deltaText.resolveOrNull(),
                    tooltipDescription =
                        stringResource(
                            app.readylytics.health.core.ui.R.string.tooltip_sleep_duration,
                            goalText,
                        ),
                )
            }
        }

        if (uiState.isLoading) {
            SkeletonCard(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                height = 120.dp,
            )
        } else {
            TrendCard(
                title = stringResource(R.string.sleep_breakdown_title),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                SleepArchitectureBar(
                    session = singleSessionVisual,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        if (uiState.isLoading) {
            SkeletonCard(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                height = 260.dp,
            )
        } else {
            TrendCard(
                title = stringResource(R.string.sleep_timeline_title),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                SleepStagesChart(
                    session = singleSessionVisual,
                    stageTimeline = uiState.stageTimeline,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

        SectionHeader(
            title = stringResource(R.string.sleep_trend_section_title),
            enabled = !uiState.isLoading,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
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
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        if (uiState.isLoading) {
            SleepTrendSkeleton(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal))
        } else {
            SleepTrendCard(
                selectedRange = uiState.selectedTrendRange,
                startOffsetPoints = uiState.trendStartOffsetPoints,
                durationSpanPoints = uiState.trendDurationSpanPoints,
                actualDurationPoints = uiState.trendActualDurationPoints,
                rangeStartMs = uiState.trendRangeStartMs,
                scrollState = trendScrollState,
                zoomState = trendZoomState,
                parentScrollInProgress = { scrollState.isScrollInProgress },
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            )
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        SectionHeader(title = stringResource(R.string.sleep_metrics_title))
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        if (uiState.isLoading) {
            MetricsGridSkeleton()
        } else {
            MetricsGrid(
                uiState = uiState,
                circadianResult = circadianConsistency,
                singleSessionVisual = singleSessionVisual,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            )
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

        StatusLegend()
    }
}

@Composable
private fun MetricsGrid(
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    singleSessionVisual: SleepSessionData?,
    modifier: Modifier = Modifier,
) {
    val session = singleSessionVisual
    val summary = uiState.latestSummary
    val metrics = uiState.latestMetrics

    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.NO_DATA
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.NO_DATA
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.NO_DATA

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val scoreText =
                    when (circadianResult) {
                        is CircadianConsistencyResult.Calibrating ->
                            stringResource(
                                app.readylytics.health.core.ui.R.string.spo2_calibrating,
                            )
                        is CircadianConsistencyResult.MissingData -> "—"
                        is CircadianConsistencyResult.Ready -> "${circadianResult.score.roundToPercentInt()}%"
                    }
                val windowText =
                    when (circadianResult) {
                        is CircadianConsistencyResult.Calibrating,
                        is CircadianConsistencyResult.MissingData,
                        -> null
                        is CircadianConsistencyResult.Ready ->
                            stringResource(
                                app.readylytics.health.core.ui.R.string.label_circadian_median,
                                circadianResult.medianBedtimeMinutes.toTimeString(),
                                circadianResult.medianWakeMinutes.toTimeString(),
                            )
                    }
                val thresholdMinutes =
                    when (circadianResult) {
                        is CircadianConsistencyResult.Calibrating,
                        is CircadianConsistencyResult.MissingData,
                        -> 30
                        is CircadianConsistencyResult.Ready -> circadianResult.thresholdMinutes
                    }
                val tooltipText =
                    stringResource(app.readylytics.health.core.ui.R.string.tooltip_circadian_score, thresholdMinutes)

                CircadianConsistencyCard(
                    scoreText = scoreText,
                    windowText = windowText,
                    status = circadianResult.toStatus(),
                    tooltipText = tooltipText,
                    onClick = null,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCard(
                    title = stringResource(app.readylytics.health.core.ui.R.string.card_title_sleep_efficiency),
                    value =
                        session?.let {
                            stringResource(
                                app.readylytics.health.core.ui.R.string.card_efficiency_format,
                                it.efficiency.roundToPercentInt(),
                            )
                        }
                            ?: "—",
                    secondaryText = stringResource(app.readylytics.health.core.ui.R.string.card_goal_sleep_efficiency),
                    status = efficiencyStatus,
                    tooltip = stringResource(app.readylytics.health.core.ui.R.string.card_tooltip_sleep_efficiency),
                    onClick = null,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
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
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
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
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
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

internal fun resolveSingleSessionVisual(
    session: SleepSessionData?,
    summary: app.readylytics.health.domain.model.DailySummary?,
): SleepSessionData? {
    val actualMinutes = actualSleepMinutes(session) ?: return session
    val summaryMinutes = summary?.sleepDurationMinutes ?: return session
    if (actualMinutes != summaryMinutes) {
        // Biphasic days can carry aggregate totals that exceed any single session. Keep the
        // best available session visual instead of collapsing architecture/timeline to blanks.
        return session
    }
    return session
}
