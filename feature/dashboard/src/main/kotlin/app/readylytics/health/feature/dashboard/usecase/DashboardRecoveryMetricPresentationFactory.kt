package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.components.GOAL_FILL_CAP_FRACTION
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.model.BaselineArrow
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.PersonalBaselineAssessment
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.model.rasStatus
import app.readylytics.health.domain.model.sleepDurationStatus
import app.readylytics.health.domain.preferences.UserPreferences
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

internal class DashboardRecoveryMetricPresentationFactory(
    private val resourceProvider: ResourceProvider,
) {
    fun build(
        summary: DailySummary?,
        metrics: DailyMetrics?,
        preferences: UserPreferences,
        lastSleepSession: SleepSessionSummary?,
        hrvAssessment: PersonalBaselineAssessment,
        rhrAssessment: PersonalBaselineAssessment,
        todayRasIncrease: Float? = null,
    ): Map<CardId, UniversalMetricPresentation> =
        mapOf(
            CardId.SLEEP_DURATION to
                sleepDurationPresentation(summary, metrics, preferences, lastSleepSession),
            CardId.HRV to hrvPresentation(metrics, preferences, hrvAssessment),
            CardId.SLEEP_RHR to rhrPresentation(metrics, preferences, rhrAssessment, isSleep = true),
            CardId.RESTING_HR to rhrPresentation(metrics, preferences, rhrAssessment, isSleep = false),
            CardId.RAS_DAILY to rasPresentation(summary, metrics, preferences, todayRasIncrease),
        )

    private fun sleepDurationPresentation(
        summary: DailySummary?,
        metrics: DailyMetrics?,
        preferences: UserPreferences,
        lastSleepSession: SleepSessionSummary?,
    ): UniversalMetricPresentation {
        val goalMinutes = (preferences.goalSleepHours * 60).toInt()
        val durationVisual =
            UniversalMetricScalePreparer.goal(
                value = summary?.sleepDurationMinutes?.toFloat(),
                target = goalMinutes.toFloat(),
            )
        val title = resourceProvider.getString(DashboardR.string.card_title_sleep_duration)
        val valueText = metrics?.sleepDurationDisplay ?: "—"
        val goalText = DailyMetricsMapper.formatSleepDuration(goalMinutes) ?: "—"
        val status = summary?.sleepDurationStatus(goalMinutes) ?: MetricStatus.CALIBRATING
        val reason = durationVisual.unavailableReason
        val description =
            when {
                reason != null ->
                    unavailableDescription(title, reason)
                durationVisual.isAboveTarget ->
                    resourceProvider.getString(
                        DashboardR.string.semantics_goal_above_target_status_format,
                        title,
                        valueText,
                        classificationText(status),
                    )
                else ->
                    resourceProvider.getString(
                        DashboardR.string.semantics_goal_status_format,
                        title,
                        valueText,
                        goalText,
                        classificationText(status),
                    )
            }
        val tooltip = resourceProvider.getString(CoreUiR.string.tooltip_sleep_duration, goalText)
        val durationSplit = DailyMetricsMapper.formatSleepDurationSplit(summary?.sleepDurationMinutes)

        return UniversalMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = "",
            gaugeValueTextOverride = durationSplit?.first,
            gaugeUnitTextOverride = durationSplit?.second,
            secondaryText =
                lastSleepSession?.let { session ->
                    resourceProvider.getString(
                        DashboardR.string.sleep_session_time_range_format,
                        formatTime(session.startTime),
                        formatTime(session.endTime),
                    )
                },
            status = status,
            tooltip = tooltip,
            accessibilityDescription = description,
            visual = durationVisual,
        )
    }

    private fun hrvPresentation(
        metrics: DailyMetrics?,
        prefs: UserPreferences,
        assessment: PersonalBaselineAssessment,
    ): UniversalMetricPresentation {
        val baseline = assessment.baseline?.toFloat()
        val poorRatio = prefs.hrvWarningThreshold - (1f - prefs.hrvWarningThreshold)
        val visual =
            UniversalMetricScalePreparer.personalBaseline(
                value = assessment.value?.toFloat(),
                baseline = baseline,
                axisMinimumRatio = poorRatio,
                axisMaximumRatio = 1f + (1f - poorRatio),
                baselineReady = baseline != null && baseline > 0f,
            )
        val status = assessment.status
        val title = resourceProvider.getString(DashboardR.string.card_title_hrv)
        val valueText = metrics?.nocturnalHrvRounded?.toString() ?: "—"
        val unitText = resourceProvider.getString(CoreUiR.string.unit_ms)

        return UniversalMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = unitText,
            secondaryText =
                baselineDeltaText(
                    arrow = baselineArrow(assessment.delta),
                    difference = assessment.delta,
                    unitText = unitText,
                ),
            status = status,
            tooltip = hrvTooltip(metrics, assessment),
            accessibilityDescription =
                visual.unavailableReason?.let { unavailableDescription(title, it) }
                    ?: personalBaselineDescription(
                        title,
                        "$valueText $unitText",
                        visual.ratio,
                        prefs.hrvOptimalThreshold,
                        prefs.hrvWarningThreshold,
                        higherIsBetter = true,
                        status = status,
                    ),
            visual = visual,
        )
    }

    private fun rhrPresentation(
        metrics: DailyMetrics?,
        prefs: UserPreferences,
        assessment: PersonalBaselineAssessment,
        isSleep: Boolean,
    ): UniversalMetricPresentation {
        val visual = rhrVisual(assessment, prefs)
        val title =
            resourceProvider.getString(
                if (isSleep) DashboardR.string.card_title_sleep_rhr else DashboardR.string.card_title_resting_hr,
            )
        val valueText = metrics?.restingHeartRateRounded?.toString() ?: "—"
        val unitText = resourceProvider.getString(CoreUiR.string.unit_bpm)
        val status = assessment.status
        val arrow = baselineArrow(assessment.delta)
        val difference = assessment.delta

        return UniversalMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = unitText,
            secondaryText = baselineDeltaText(arrow, difference, unitText),
            status = status,
            tooltip = rhrTooltip(assessment, isSleep),
            accessibilityDescription =
                visual.unavailableReason?.let { unavailableDescription(title, it) }
                    ?: personalBaselineDescription(
                        title,
                        "$valueText $unitText",
                        visual.ratio,
                        prefs.rhrOptimalThreshold,
                        prefs.rhrWarningThreshold,
                        higherIsBetter = false,
                        status = status,
                    ),
            visual = visual,
        )
    }

    private fun rasPresentation(
        summary: DailySummary?,
        metrics: DailyMetrics?,
        preferences: UserPreferences,
        todayRasIncrease: Float? = null,
    ): UniversalMetricPresentation {
        val value =
            summary?.let {
                LoadSourceSelector.selectTotalRas(it, preferences.rasSourceMode)
            }
        val visual =
            UniversalMetricScalePreparer.score(
                value,
                0f,
                100f / GOAL_FILL_CAP_FRACTION,
            )
        val title = resourceProvider.getString(DashboardR.string.card_title_ras_daily)
        val valueText = metrics?.rasRounded?.toString() ?: "—"
        val status = value.rasStatus()
        val rasIncreaseText =
            todayRasIncrease?.let { increase ->
                if (increase > 0.005f) {
                    resourceProvider.getString(
                        CoreUiR.string.delta_up_format,
                        resourceProvider.getString(CoreUiR.string.delta_up),
                        increase.roundToInt().toString(),
                    )
                } else {
                    resourceProvider.getString(CoreUiR.string.delta_no_change)
                }
            }

        return UniversalMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = "",
            secondaryText = rasIncreaseText,
            status = status,
            tooltip = resourceProvider.getString(CoreUiR.string.tooltip_ras),
            accessibilityDescription =
                visual.unavailableReason?.let { unavailableDescription(title, it) }
                    ?: resourceProvider.getString(
                        DashboardR.string.semantics_score_format,
                        title,
                        valueText,
                        "100",
                        classificationText(status),
                    ),
            visual = visual,
        )
    }

    private fun rhrVisual(
        assessment: PersonalBaselineAssessment,
        prefs: UserPreferences,
    ) = run {
        val baseline = assessment.baseline?.toFloat()
        val poorRatio = prefs.rhrWarningThreshold + (prefs.rhrWarningThreshold - 1f)
        UniversalMetricScalePreparer.personalBaseline(
            value = assessment.value?.toFloat(),
            baseline = baseline,
            axisMinimumRatio = 1f - (poorRatio - 1f),
            axisMaximumRatio = poorRatio,
            baselineReady = baseline != null && baseline > 0f,
        )
    }

    private fun personalBaselineDescription(
        title: String,
        valueText: String,
        ratio: Float?,
        optimalThreshold: Float,
        warningThreshold: Float,
        higherIsBetter: Boolean,
        status: MetricStatus,
    ): String {
        val lowerBound = 1f - (warningThreshold + (warningThreshold - 1f) - 1f)
        val relation =
            when {
                higherIsBetter && ratio != null && ratio >= optimalThreshold ->
                    DashboardR.string.personal_baseline_above_range_description
                higherIsBetter && ratio != null && ratio <= warningThreshold ->
                    DashboardR.string.personal_baseline_below_range_description
                !higherIsBetter && ratio != null && ratio > warningThreshold ->
                    DashboardR.string.personal_baseline_above_range_description
                !higherIsBetter && ratio != null && ratio < lowerBound ->
                    DashboardR.string.personal_baseline_below_range_description
                else -> DashboardR.string.personal_baseline_within_range_description
            }
        return resourceProvider.getString(
            DashboardR.string.semantics_value_note_status_format,
            title,
            valueText,
            resourceProvider.getString(relation),
            classificationText(status),
        )
    }

    private fun hrvTooltip(
        metrics: DailyMetrics?,
        assessment: PersonalBaselineAssessment,
    ): String =
        buildString {
            append(resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv))
            val baseline = assessment.baseline
            val arrow = baselineArrow(assessment.delta)?.symbol
            val difference = assessment.delta?.let(::abs)
            when {
                baseline == null ->
                    append(resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv_no_baseline))
                arrow != null && difference != null ->
                    append(
                        resourceProvider.getString(
                            CoreUiR.string.tooltip_sleep_hrv_baseline,
                            baseline,
                            arrow,
                            difference,
                        ),
                    )
                else ->
                    append(
                        resourceProvider.getString(
                            CoreUiR.string.tooltip_sleep_hrv_baseline_no_today,
                            baseline,
                        ),
                    )
            }
            val zScore = metrics?.zLnHrvDisplay
            val sigma = metrics?.hrvSigmaDisplay
            if (zScore != null && sigma != null) {
                append(
                    resourceProvider.getString(
                        CoreUiR.string.tooltip_sleep_hrv_diagnostics,
                        zScore,
                        sigma,
                    ),
                )
            }
        }

    private fun rhrTooltip(
        assessment: PersonalBaselineAssessment,
        isSleep: Boolean,
    ): String {
        val baseline = assessment.baseline
        val arrow = baselineArrow(assessment.delta)?.symbol
        val difference = assessment.delta?.let(::abs)
        val tooltipResources =
            if (isSleep) {
                CoreUiR.string.tooltip_sleep_rhr_baseline to CoreUiR.string.tooltip_sleep_rhr_no_baseline
            } else {
                DashboardR.string.tooltip_resting_hr_baseline to DashboardR.string.tooltip_resting_hr_no_baseline
            }
        val details =
            if (baseline != null && arrow != null && difference != null) {
                resourceProvider.getString(
                    tooltipResources.first,
                    baseline,
                    arrow,
                    difference,
                )
            } else {
                resourceProvider.getString(tooltipResources.second)
            }
        return if (isSleep) resourceProvider.getString(CoreUiR.string.tooltip_sleep_rhr) + details else details
    }

    private fun baselineArrow(delta: Int?): BaselineArrow? =
        when {
            delta == null -> null
            delta > 0 -> BaselineArrow.UP
            delta < 0 -> BaselineArrow.DOWN
            else -> BaselineArrow.EQUAL
        }

    private fun baselineDeltaText(
        arrow: BaselineArrow?,
        difference: Int?,
        unitText: String,
    ): String? {
        if (arrow == null || difference == null) return null

        return when (arrow) {
            BaselineArrow.EQUAL -> resourceProvider.getString(CoreUiR.string.delta_no_change)
            BaselineArrow.UP ->
                resourceProvider.getString(
                    CoreUiR.string.delta_up_format,
                    resourceProvider.getString(CoreUiR.string.delta_up),
                    "$difference $unitText",
                )
            BaselineArrow.DOWN ->
                resourceProvider.getString(
                    CoreUiR.string.delta_up_format,
                    resourceProvider.getString(CoreUiR.string.delta_down),
                    "${abs(difference)} $unitText",
                )
        }
    }

    private fun formatTime(timestamp: Long): String = DateFormatUtils.epochMilliToTimeString(timestamp)

    private fun classificationText(status: MetricStatus): String =
        resourceProvider.getString(
            when (status) {
                MetricStatus.OPTIMAL -> CoreUiR.string.metric_status_optimal
                MetricStatus.NEUTRAL -> CoreUiR.string.metric_status_neutral
                MetricStatus.WARNING -> CoreUiR.string.metric_status_warning
                MetricStatus.POOR -> CoreUiR.string.metric_status_poor
                MetricStatus.NO_DATA,
                MetricStatus.CALIBRATING,
                -> CoreUiR.string.metric_status_calibrating
            },
        )

    private fun unavailableDescription(
        title: String,
        reason: UniversalMetricUnavailableReason,
    ): String =
        resourceProvider.getString(
            DashboardR.string.semantics_unavailable_format,
            title,
            resourceProvider.getString(
                when (reason) {
                    UniversalMetricUnavailableReason.MISSING_VALUE ->
                        CoreUiR.string.metric_unavailable_missing_value
                    UniversalMetricUnavailableReason.MISSING_TARGET ->
                        CoreUiR.string.metric_unavailable_missing_target
                    UniversalMetricUnavailableReason.BASELINE_NOT_READY ->
                        CoreUiR.string.metric_unavailable_baseline_not_ready
                    UniversalMetricUnavailableReason.MISSING_BMI ->
                        CoreUiR.string.metric_unavailable_missing_bmi
                },
            ),
        )
}
