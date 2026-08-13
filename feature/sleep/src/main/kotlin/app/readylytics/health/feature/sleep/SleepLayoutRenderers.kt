package app.readylytics.health.feature.sleep

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
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
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.core.ui.R as CoreUiR

/** Full-width top cards (architecture bar, stages timeline, HR chart). The two gauges pair up. */
val SLEEP_TOP_CARD_FULL_WIDTH_IDS: Set<SleepTopCardId> =
    setOf(
        SleepTopCardId.SLEEP_BREAKDOWN_BAR,
        SleepTopCardId.SLEEP_STAGES_TIMELINE,
        SleepTopCardId.SLEEP_HR_CHART,
    )

@Composable
fun rememberSleepTopCardDataMap(
    uiState: SleepUiState,
    singleSessionVisual: SleepSessionData?,
): Map<SleepTopCardId, @Composable (SleepTopCardConfiguration) -> Unit> =
    remember(uiState, singleSessionVisual) {
        buildSleepTopCardDataMap(uiState, singleSessionVisual)
    }

/** Pure builder — unit-testable without composition. */
fun buildSleepTopCardDataMap(
    uiState: SleepUiState,
    singleSessionVisual: SleepSessionData?,
): Map<SleepTopCardId, @Composable (SleepTopCardConfiguration) -> Unit> =
    mapOf(
        SleepTopCardId.SLEEP_SCORE to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    ScoreDialSkeleton()
                } else {
                    SleepScoreCard(
                        title = stringResource(R.string.sleep_score_gauge_title),
                        score = uiState.latestSummary?.sleepScore,
                        displayText =
                            uiState.latestMetrics?.sleepScoreRounded?.toString()
                                ?: stringResource(CoreUiR.string.metric_value_unavailable),
                        unitText = "",
                        deltaText =
                            formatRoundedScoreDelta(
                                currentRounded = uiState.latestMetrics?.sleepScoreRounded,
                                previousRounded = uiState.yesterdaySleepScoreRounded,
                            ).resolveOrNull(),
                        tooltipDescription = stringResource(CoreUiR.string.tooltip_sleep_score),
                    )
                }
            },
        SleepTopCardId.SLEEP_DURATION_GAUGE to
            @Composable { config: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    ScoreDialSkeleton()
                } else {
                    val sleepTimeGaugeData = uiState.sleepTimeGaugeData
                    val goalText =
                        DateFormatUtils.formatSleepDuration(
                            (uiState.goalSleepHours * 60f).toInt().coerceAtLeast(0),
                        )
                    SleepMetricCard(
                        title = stringResource(R.string.sleep_time_gauge_title),
                        rawValue = sleepTimeGaugeData.progress,
                        valueText = sleepTimeGaugeData.gaugeValueText,
                        unitText = sleepTimeGaugeData.gaugeUnitText,
                        maxScore = 1f,
                        status = sleepTimeGaugeData.status,
                        deltaText = sleepTimeGaugeData.deltaText.resolveOrNull(),
                        mode =
                            config.requestedDisplayMode?.toUniversalMode()
                                ?: UniversalCardDisplayMode.GAUGE,
                        tooltip = stringResource(CoreUiR.string.tooltip_sleep_duration, goalText),
                    )
                }
            },
        SleepTopCardId.SLEEP_BREAKDOWN_BAR to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    SkeletonCard(height = 120.dp)
                } else {
                    TrendCard(title = stringResource(R.string.sleep_breakdown_title)) {
                        SleepArchitectureBar(
                            session = singleSessionVisual,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        SleepTopCardId.SLEEP_STAGES_TIMELINE to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    SkeletonCard(height = 260.dp)
                } else {
                    TrendCard(title = stringResource(R.string.sleep_timeline_title)) {
                        SleepStagesChart(
                            session = singleSessionVisual,
                            stageTimeline = uiState.stageTimeline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        SleepTopCardId.SLEEP_HR_CHART to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    SkeletonCard(height = 260.dp)
                } else {
                    TrendCard(title = stringResource(R.string.sleep_hr_chart_title)) {
                        SleepHrChart(
                            session = singleSessionVisual,
                            samples = uiState.sleepHrSamples,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
    )

@Composable
fun rememberSleepMetricCardDataMap(
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    singleSessionVisual: SleepSessionData?,
): Map<SleepMetricCardId, @Composable (SleepMetricCardConfiguration) -> Unit> =
    remember(uiState, circadianResult, singleSessionVisual) {
        buildSleepMetricCardDataMap(uiState, circadianResult, singleSessionVisual)
    }

/** Pure builder — unit-testable without composition. */
fun buildSleepMetricCardDataMap(
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    singleSessionVisual: SleepSessionData?,
): Map<SleepMetricCardId, @Composable (SleepMetricCardConfiguration) -> Unit> {
    val session = singleSessionVisual
    val summary = uiState.latestSummary
    val metrics = uiState.latestMetrics

    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.NO_DATA
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.NO_DATA
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.NO_DATA

    return mapOf(
        SleepMetricCardId.CIRCADIAN_CONSISTENCY to
            @Composable { config: SleepMetricCardConfiguration ->
                val scoreText =
                    when (circadianResult) {
                        is CircadianConsistencyResult.Calibrating ->
                            stringResource(CoreUiR.string.spo2_calibrating)
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
                                CoreUiR.string.label_circadian_median,
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
                    stringResource(CoreUiR.string.tooltip_circadian_score, thresholdMinutes)

                SleepMetricCard(
                    title = stringResource(CoreUiR.string.label_circadian_consistency),
                    valueText = scoreText,
                    secondaryText = windowText,
                    status = circadianResult.toStatus(),
                    tooltip = tooltipText,
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.SLEEP_EFFICIENCY to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(CoreUiR.string.card_title_sleep_efficiency),
                    valueText =
                        session?.let {
                            stringResource(
                                CoreUiR.string.card_efficiency_format,
                                it.efficiency.roundToPercentInt(),
                            )
                        } ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    secondaryText = stringResource(CoreUiR.string.card_goal_sleep_efficiency),
                    status = efficiencyStatus,
                    tooltip = stringResource(CoreUiR.string.card_tooltip_sleep_efficiency),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.DEEP_SLEEP to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_deep_sleep),
                    valueText =
                        metrics?.deepSleepPercentDisplay
                            ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    secondaryText = stringResource(R.string.card_target_deep_sleep),
                    status = deepStatus,
                    tooltip = stringResource(R.string.tooltip_deep_sleep),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.REM_SLEEP to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_rem_sleep),
                    valueText =
                        metrics?.remSleepPercentDisplay
                            ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    secondaryText = stringResource(R.string.card_target_rem_sleep),
                    status = remStatus,
                    tooltip = stringResource(R.string.tooltip_rem_sleep),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.NAP_DURATION to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_nap_duration),
                    valueText = metrics?.napDurationDisplay ?: DateFormatUtils.formatSleepDuration(0),
                    status = MetricStatus.NEUTRAL,
                    tooltip = stringResource(R.string.tooltip_nap_duration),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.NAP_COUNT to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_nap_count),
                    valueText = metrics?.napCount?.toString() ?: "0",
                    status = MetricStatus.NEUTRAL,
                    tooltip = stringResource(R.string.tooltip_nap_count),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
    )
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

private fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode =
    when (this) {
        DashboardCardDisplayMode.GAUGE -> UniversalCardDisplayMode.GAUGE
        DashboardCardDisplayMode.BAR -> UniversalCardDisplayMode.BAR
        DashboardCardDisplayMode.VALUE -> UniversalCardDisplayMode.VALUE
    }
