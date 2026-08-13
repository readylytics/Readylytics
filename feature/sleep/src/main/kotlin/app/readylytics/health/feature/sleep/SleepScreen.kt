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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.readylytics.health.core.ui.components.EditModeFab
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.deepSleepStatus
import app.readylytics.health.domain.model.efficiencyStatus
import app.readylytics.health.domain.model.remSleepStatus
import app.readylytics.health.domain.model.scoreStatus
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.toStatus
import app.readylytics.health.domain.scoring.toTimeString
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.feature.sleep.overview.SleepManagementBottomSheet
import kotlinx.coroutines.launch
import app.readylytics.health.core.ui.R as CoreUiR

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
        onToggleSleepManagement = viewModel::toggleSleepLayoutManagement,
        onCancelSleepManagement = viewModel::onCancelSleepLayoutManagement,
        onToggleSleepTopCardVisibility = viewModel::onToggleSleepTopCardVisibility,
        onReorderSleepTopCards = viewModel::onReorderSleepTopCards,
        onSleepTopCardDisplayModeChanged = viewModel::onSleepTopCardDisplayModeChanged,
        onToggleSleepChartVisibility = viewModel::onToggleSleepChartVisibility,
        onReorderSleepCharts = viewModel::onReorderSleepCharts,
        onToggleSleepMetricCardVisibility = viewModel::onToggleSleepMetricCardVisibility,
        onReorderSleepMetricCards = viewModel::onReorderSleepMetricCards,
        onSleepMetricCardDisplayModeChanged = viewModel::onSleepMetricCardDisplayModeChanged,
        onResetSleepLayoutToDefaults = viewModel::onResetSleepLayoutToDefaults,
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
    onToggleSleepManagement: () -> Unit = {},
    onCancelSleepManagement: () -> Unit = {},
    onToggleSleepTopCardVisibility: (SleepTopCardId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepTopCards: (List<SleepTopCardConfiguration>) -> Unit = {},
    onSleepTopCardDisplayModeChanged: (SleepTopCardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
    onToggleSleepChartVisibility: (SleepChartId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepCharts: (List<SleepChartConfiguration>) -> Unit = {},
    onToggleSleepMetricCardVisibility: (SleepMetricCardId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepMetricCards: (List<SleepMetricCardConfiguration>) -> Unit = {},
    onSleepMetricCardDisplayModeChanged: (SleepMetricCardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
    onResetSleepLayoutToDefaults: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showSleepManagement by rememberSaveable { mutableStateOf(false) }

    val singleSessionVisual = uiState.latestSession
    val (trendScrollState, trendZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedTrendRange.days,
            key = uiState.selectedTrendRange,
        )

    val visibleTopCards =
        remember(uiState.sleepTopCardConfigurations) {
            uiState.sleepTopCardConfigurations.filter { it.isVisible }.sortedBy { it.position }
        }
    val visibleCharts =
        remember(uiState.sleepChartConfigurations) {
            uiState.sleepChartConfigurations.filter { it.isVisible }.sortedBy { it.position }
        }
    val visibleMetricCards =
        remember(uiState.sleepMetricCardConfigurations) {
            uiState.sleepMetricCardConfigurations.filter { it.isVisible }.sortedBy { it.position }
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (showSleepManagement) {
            SleepManagementBottomSheet(
                topCardConfigurations = uiState.sleepTopCardConfigurations,
                chartConfigurations = uiState.sleepChartConfigurations,
                metricCardConfigurations = uiState.sleepMetricCardConfigurations,
                onTopCardVisibilityChanged = onToggleSleepTopCardVisibility,
                onChartVisibilityChanged = onToggleSleepChartVisibility,
                onMetricCardVisibilityChanged = onToggleSleepMetricCardVisibility,
                onTopCardDisplayModeChanged = onSleepTopCardDisplayModeChanged,
                onMetricCardDisplayModeChanged = onSleepMetricCardDisplayModeChanged,
                onTopCardReordered = onReorderSleepTopCards,
                onChartReordered = onReorderSleepCharts,
                onMetricCardReordered = onReorderSleepMetricCards,
                onResetToDefaults = onResetSleepLayoutToDefaults,
                onDismiss = {
                    scope.launch { sheetState.hide() }
                    showSleepManagement = false
                },
                sheetState = sheetState,
            )
        }

        Column(
            modifier =
                Modifier
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

            var cardIndex = 0
            while (cardIndex < visibleTopCards.size) {
                val card = visibleTopCards[cardIndex]
                val nextCard = visibleTopCards.getOrNull(cardIndex + 1)
                val isGauge =
                    card.cardId == SleepTopCardId.SLEEP_SCORE || card.cardId == SleepTopCardId.SLEEP_DURATION_GAUGE
                val isNextGauge =
                    nextCard != null &&
                        (
                            nextCard.cardId == SleepTopCardId.SLEEP_SCORE ||
                                nextCard.cardId == SleepTopCardId.SLEEP_DURATION_GAUGE
                        )

                if (isGauge &&
                    nextCard != null &&
                    (
                        nextCard.cardId == SleepTopCardId.SLEEP_SCORE ||
                            nextCard.cardId == SleepTopCardId.SLEEP_DURATION_GAUGE
                    )
                ) {
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
                        RenderTopCard(card, uiState, singleSessionVisual, Modifier.weight(1f))
                        RenderTopCard(nextCard, uiState, singleSessionVisual, Modifier.weight(1f))
                    }
                    cardIndex += 2
                } else if (isGauge) {
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
                        RenderTopCard(card, uiState, singleSessionVisual, Modifier.weight(1f))
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    cardIndex += 1
                } else {
                    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
                    RenderTopCard(card, uiState, singleSessionVisual, Modifier.fillMaxWidth())
                    cardIndex += 1
                }
            }

            if (visibleCharts.any { it.chartId == SleepChartId.SLEEP_DURATION_TREND }) {
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
                        trendDays = uiState.trendDays,
                        rangeStartMs = uiState.trendRangeStartMs,
                        scoringZoneId = uiState.trendScoringZoneId,
                        scrollState = trendScrollState,
                        zoomState = trendZoomState,
                        parentScrollInProgress = { scrollState.isScrollInProgress },
                        actualDurationSummary = uiState.trendActualDurationSummary,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    )
                }
            }

            if (visibleMetricCards.isNotEmpty()) {
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

                SectionHeader(title = stringResource(R.string.sleep_metrics_title))
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

                if (uiState.isLoading) {
                    MetricsGridSkeleton()
                } else {
                    MetricsGrid(
                        metricCardConfigurations = visibleMetricCards,
                        uiState = uiState,
                        circadianResult = circadianConsistency,
                        singleSessionVisual = singleSessionVisual,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

            StatusLegend()

            if (!uiState.isManagingSleepLayout) {
                FilledTonalButton(
                    onClick = onToggleSleepManagement,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.pageHorizontal,
                                vertical = MaterialTheme.spacing.pageSectionGap,
                            ),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Text(
                        text = stringResource(CoreUiR.string.action_customize),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        EditModeFab(
            isVisible = uiState.isManagingSleepLayout,
            onDoneClick = onToggleSleepManagement,
            onCancelClick = onCancelSleepManagement,
            onManageClick = { showSleepManagement = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacing.pageHorizontal),
        )
    }
}

@Composable
private fun RenderTopCard(
    config: SleepTopCardConfiguration,
    uiState: SleepUiState,
    singleSessionVisual: SleepSessionData?,
    modifier: Modifier = Modifier,
) {
    val mode = config.requestedDisplayMode?.toUniversalMode() ?: UniversalCardDisplayMode.GAUGE

    when (config.cardId) {
        SleepTopCardId.SLEEP_SCORE -> {
            if (uiState.isLoading) {
                ScoreDialSkeleton(modifier = modifier)
            } else {
                SleepScoreCard(
                    modifier = modifier,
                    title = stringResource(R.string.sleep_score_gauge_title),
                    score = uiState.latestSummary?.sleepScore,
                    displayText =
                        uiState.latestMetrics?.sleepScoreRounded?.toString()
                            ?: stringResource(app.readylytics.health.core.ui.R.string.metric_value_unavailable),
                    unitText = "",
                    deltaText =
                        formatRoundedScoreDelta(
                            currentRounded = uiState.latestMetrics?.sleepScoreRounded,
                            previousRounded = uiState.yesterdaySleepScoreRounded,
                        ).resolveOrNull(),
                    tooltipDescription = stringResource(app.readylytics.health.core.ui.R.string.tooltip_sleep_score),
                )
            }
        }
        SleepTopCardId.SLEEP_DURATION_GAUGE -> {
            if (uiState.isLoading) {
                ScoreDialSkeleton(modifier = modifier)
            } else {
                val sleepTimeGaugeData = uiState.sleepTimeGaugeData
                val goalText =
                    DateFormatUtils.formatSleepDuration(
                        (uiState.goalSleepHours * 60f).toInt().coerceAtLeast(0),
                    )

                SleepMetricCard(
                    modifier = modifier,
                    title = stringResource(R.string.sleep_time_gauge_title),
                    rawValue = sleepTimeGaugeData.progress,
                    valueText = sleepTimeGaugeData.gaugeValueText,
                    unitText = sleepTimeGaugeData.gaugeUnitText,
                    maxScore = 1f,
                    status = sleepTimeGaugeData.status,
                    deltaText = sleepTimeGaugeData.deltaText.resolveOrNull(),
                    mode = mode,
                    tooltip =
                        stringResource(
                            app.readylytics.health.core.ui.R.string.tooltip_sleep_duration,
                            goalText,
                        ),
                )
            }
        }
        SleepTopCardId.SLEEP_BREAKDOWN_BAR -> {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    height = 120.dp,
                )
            } else {
                TrendCard(
                    title = stringResource(R.string.sleep_breakdown_title),
                    modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    SleepArchitectureBar(
                        session = singleSessionVisual,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        SleepTopCardId.SLEEP_STAGES_TIMELINE -> {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    height = 260.dp,
                )
            } else {
                TrendCard(
                    title = stringResource(R.string.sleep_timeline_title),
                    modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    SleepStagesChart(
                        session = singleSessionVisual,
                        stageTimeline = uiState.stageTimeline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        SleepTopCardId.SLEEP_HR_CHART -> {
            if (uiState.isLoading) {
                SkeletonCard(
                    modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    height = 260.dp,
                )
            } else {
                TrendCard(
                    title = stringResource(R.string.sleep_hr_chart_title),
                    modifier = modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    SleepHrChart(
                        session = singleSessionVisual,
                        samples = uiState.sleepHrSamples,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsGrid(
    metricCardConfigurations: List<SleepMetricCardConfiguration>,
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    singleSessionVisual: SleepSessionData?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
    ) {
        metricCardConfigurations.chunked(2).forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
            ) {
                rowCards.forEach { cardConfig ->
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        MetricGridCardItem(
                            cardConfig = cardConfig,
                            uiState = uiState,
                            circadianResult = circadianResult,
                            singleSessionVisual = singleSessionVisual,
                        )
                    }
                }
                if (rowCards.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricGridCardItem(
    cardConfig: SleepMetricCardConfiguration,
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    singleSessionVisual: SleepSessionData?,
) {
    val session = singleSessionVisual
    val summary = uiState.latestSummary
    val metrics = uiState.latestMetrics

    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.NO_DATA
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.NO_DATA
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.NO_DATA

    val mode =
        cardConfig.requestedDisplayMode?.toUniversalMode()
            ?: UniversalCardDisplayMode.VALUE

    when (cardConfig.cardId) {
        SleepMetricCardId.CIRCADIAN_CONSISTENCY -> {
            val scoreText =
                when (circadianResult) {
                    is CircadianConsistencyResult.Calibrating ->
                        stringResource(app.readylytics.health.core.ui.R.string.spo2_calibrating)
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

            SleepMetricCard(
                title = stringResource(app.readylytics.health.core.ui.R.string.label_circadian_consistency),
                valueText = scoreText,
                secondaryText = windowText,
                status = circadianResult.toStatus(),
                tooltip = tooltipText,
                mode = mode,
            )
        }
        SleepMetricCardId.SLEEP_EFFICIENCY -> {
            SleepMetricCard(
                title = stringResource(app.readylytics.health.core.ui.R.string.card_title_sleep_efficiency),
                valueText =
                    session?.let {
                        stringResource(
                            app.readylytics.health.core.ui.R.string.card_efficiency_format,
                            it.efficiency.roundToPercentInt(),
                        )
                    } ?: stringResource(app.readylytics.health.core.ui.R.string.metric_value_unavailable),
                secondaryText = stringResource(app.readylytics.health.core.ui.R.string.card_goal_sleep_efficiency),
                status = efficiencyStatus,
                tooltip = stringResource(app.readylytics.health.core.ui.R.string.card_tooltip_sleep_efficiency),
                mode = mode,
            )
        }
        SleepMetricCardId.DEEP_SLEEP -> {
            SleepMetricCard(
                title = stringResource(R.string.card_title_deep_sleep),
                valueText =
                    metrics?.deepSleepPercentDisplay
                        ?: stringResource(app.readylytics.health.core.ui.R.string.metric_value_unavailable),
                secondaryText = stringResource(R.string.card_target_deep_sleep),
                status = deepStatus,
                tooltip = stringResource(R.string.tooltip_deep_sleep),
                mode = mode,
            )
        }
        SleepMetricCardId.REM_SLEEP -> {
            SleepMetricCard(
                title = stringResource(R.string.card_title_rem_sleep),
                valueText =
                    metrics?.remSleepPercentDisplay
                        ?: stringResource(app.readylytics.health.core.ui.R.string.metric_value_unavailable),
                secondaryText = stringResource(R.string.card_target_rem_sleep),
                status = remStatus,
                tooltip = stringResource(R.string.tooltip_rem_sleep),
                mode = mode,
            )
        }
        SleepMetricCardId.NAP_DURATION -> {
            SleepMetricCard(
                title = stringResource(R.string.card_title_nap_duration),
                valueText = metrics?.napDurationDisplay ?: DateFormatUtils.formatSleepDuration(0),
                status = MetricStatus.NEUTRAL,
                tooltip = stringResource(R.string.tooltip_nap_duration),
                mode = mode,
            )
        }
        SleepMetricCardId.NAP_COUNT -> {
            SleepMetricCard(
                title = stringResource(R.string.card_title_nap_count),
                valueText = metrics?.napCount?.toString() ?: "0",
                status = MetricStatus.NEUTRAL,
                tooltip = stringResource(R.string.tooltip_nap_count),
                mode = mode,
            )
        }
    }
}

@Composable
private fun SleepScoreCard(
    score: Float?,
    displayText: String,
    unitText: String,
    deltaText: String?,
    tooltipDescription: String,
    modifier: Modifier = Modifier,
    title: String,
) {
    SleepMetricCard(
        title = title,
        rawValue = score,
        valueText = displayText,
        unitText = unitText,
        status = score.scoreStatus(),
        tooltip = tooltipDescription,
        deltaText = deltaText,
        mode = UniversalCardDisplayMode.GAUGE,
        modifier = modifier,
    )
}

@Composable
private fun SleepMetricCard(
    title: String,
    valueText: String,
    status: MetricStatus,
    tooltip: String,
    modifier: Modifier = Modifier,
    unitText: String = "",
    secondaryText: String? = null,
    rawValue: Float? = null,
    maxScore: Float = 100f,
    deltaText: String? = null,
    mode: UniversalCardDisplayMode = UniversalCardDisplayMode.VALUE,
    tooltipDescription: String? = null,
) {
    val secondary = deltaText ?: secondaryText
    UniversalMetricCard(
        presentation =
            UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = unitText,
                secondaryText = secondary,
                status = status,
                tooltip = tooltipDescription ?: tooltip,
                accessibilityDescription = "$title: $valueText",
                visual =
                    if (mode == UniversalCardDisplayMode.GAUGE) {
                        UniversalMetricScalePreparer.score(rawValue, 0f, maxScore)
                    } else {
                        UniversalMetricVisual.ValueOnly
                    },
            ),
        specification =
            UniversalMetricCardSpec(
                supportedModes = listOf(mode),
                usesDeltaPill = deltaText != null,
            ),
        requestedMode = mode,
        modifier = modifier,
    )
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

private fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode =
    when (this) {
        DashboardCardDisplayMode.GAUGE -> UniversalCardDisplayMode.GAUGE
        DashboardCardDisplayMode.BAR -> UniversalCardDisplayMode.BAR
        DashboardCardDisplayMode.VALUE -> UniversalCardDisplayMode.VALUE
    }
