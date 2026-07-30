package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.model.BaselineArrow
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.model.hrvStatus
import app.readylytics.health.domain.model.rasStatus
import app.readylytics.health.domain.model.restingHrStatus
import app.readylytics.health.domain.model.rhrStatus
import app.readylytics.health.domain.model.sleepDurationStatus
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.feature.dashboard.DashboardMetricPresentation
import app.readylytics.health.feature.dashboard.DashboardMetricScalePreparer
import app.readylytics.health.feature.dashboard.DashboardMetricUnavailableReason
import app.readylytics.health.feature.dashboard.RawMetricBand
import kotlin.math.abs
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
    ): Map<CardId, DashboardMetricPresentation> =
        mapOf(
            CardId.SLEEP_DURATION to
                sleepDurationPresentation(summary, metrics, preferences, lastSleepSession),
            CardId.HRV to hrvPresentation(summary, metrics, preferences),
            CardId.SLEEP_RHR to rhrPresentation(summary, metrics, preferences, isSleep = true),
            CardId.RESTING_HR to rhrPresentation(summary, metrics, preferences, isSleep = false),
            CardId.RAS_DAILY to rasPresentation(metrics),
        )

    private fun sleepDurationPresentation(
        summary: DailySummary?,
        metrics: DailyMetrics?,
        preferences: UserPreferences,
        lastSleepSession: SleepSessionSummary?,
    ): DashboardMetricPresentation {
        val goalMinutes = (preferences.goalSleepHours * 60).toInt()
        val durationVisual =
            DashboardMetricScalePreparer.goal(
                value = summary?.sleepDurationMinutes?.toFloat(),
                target = goalMinutes.toFloat(),
                bands = emptyList(),
            )
        val title = resourceProvider.getString(DashboardR.string.card_title_sleep_duration)
        val valueText = metrics?.sleepDurationDisplay ?: "—"
        val goalText = DailyMetricsMapper.formatSleepDuration(goalMinutes) ?: "—"
        val description =
            when {
                durationVisual.unavailableReason != null ->
                    unavailableDescription(title, durationVisual.unavailableReason)
                durationVisual.isAboveTarget ->
                    resourceProvider.getString(
                        DashboardR.string.semantics_value_note_format,
                        title,
                        valueText,
                        resourceProvider.getString(DashboardR.string.goal_above_target_description),
                    )
                else ->
                    resourceProvider.getString(
                        DashboardR.string.semantics_goal_format,
                        title,
                        valueText,
                        goalText,
                    )
            }
        val tooltip = resourceProvider.getString(CoreUiR.string.tooltip_sleep_duration, goalText)

        return DashboardMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = "",
            secondaryText =
                lastSleepSession?.let { session ->
                    resourceProvider.getString(
                        DashboardR.string.sleep_session_time_range_format,
                        formatTime(session.startTime),
                        formatTime(session.endTime),
                    )
                },
            status = summary?.sleepDurationStatus(goalMinutes) ?: MetricStatus.CALIBRATING,
            tooltip = tooltip,
            accessibilityDescription = description,
            visual = durationVisual,
        )
    }

    private fun hrvPresentation(
        summary: DailySummary?,
        metrics: DailyMetrics?,
        prefs: UserPreferences,
    ): DashboardMetricPresentation {
        val baseline = metrics?.hrvBaselineRounded?.toFloat()
        val poorRatio = prefs.hrvWarningThreshold - (1f - prefs.hrvWarningThreshold)
        val visual =
            DashboardMetricScalePreparer.personalBaseline(
                value = summary?.nocturnalHrv?.toFloat(),
                baseline = baseline,
                axisMinimumRatio = poorRatio,
                axisMaximumRatio = 1f + (1f - poorRatio),
                bands = hrvBands(baseline, poorRatio, prefs),
                baselineReady = summary?.isCalibrating == false && baseline != null && baseline > 0f,
            )
        val status =
            summary?.hrvStatus(
                prefs.hrvOptimalThreshold,
                prefs.hrvWarningThreshold,
            ) ?: MetricStatus.CALIBRATING
        val title = resourceProvider.getString(DashboardR.string.card_title_hrv)
        val valueText = metrics?.nocturnalHrvRounded?.toString() ?: "—"
        val unitText = resourceProvider.getString(CoreUiR.string.unit_ms)

        return DashboardMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = unitText,
            secondaryText =
                baselineDeltaText(
                    arrow = metrics?.hrvBaselineArrow,
                    difference = metrics?.hrvBaselineDiff,
                    unitText = unitText,
                ),
            status = status,
            tooltip = hrvTooltip(metrics),
            accessibilityDescription =
                visual.unavailableReason?.let { unavailableDescription(title, it) }
                    ?: personalBaselineDescription(
                        title,
                        "$valueText $unitText",
                        visual.ratio,
                        prefs.hrvOptimalThreshold,
                        prefs.hrvWarningThreshold,
                        higherIsBetter = true,
                    ),
            visual = visual,
        )
    }

    private fun rhrPresentation(
        summary: DailySummary?,
        metrics: DailyMetrics?,
        prefs: UserPreferences,
        isSleep: Boolean,
    ): DashboardMetricPresentation {
        val visual = rhrVisual(summary, metrics, prefs)
        val title =
            resourceProvider.getString(
                if (isSleep) DashboardR.string.card_title_sleep_rhr else DashboardR.string.card_title_resting_hr,
            )
        val valueText = metrics?.restingHeartRateRounded?.toString() ?: "—"
        val unitText = resourceProvider.getString(CoreUiR.string.unit_bpm)
        val status =
            if (isSleep) {
                summary?.rhrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
            } else {
                summary?.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
            } ?: MetricStatus.CALIBRATING
        val (arrow, difference) =
            if (isSleep) {
                metrics?.rhrBaselineArrow to metrics?.rhrBaselineDiff
            } else {
                metrics?.restingHrBaselineArrow to metrics?.restingHrBaselineDiff
            }

        return DashboardMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = unitText,
            secondaryText = baselineDeltaText(arrow, difference, unitText),
            status = status,
            tooltip = rhrTooltip(metrics, isSleep),
            accessibilityDescription =
                visual.unavailableReason?.let { unavailableDescription(title, it) }
                    ?: personalBaselineDescription(
                        title,
                        "$valueText $unitText",
                        visual.ratio,
                        prefs.rhrOptimalThreshold,
                        prefs.rhrWarningThreshold,
                        higherIsBetter = false,
                    ),
            visual = visual,
        )
    }

    private fun rasPresentation(metrics: DailyMetrics?): DashboardMetricPresentation {
        val value = metrics?.rasRounded?.toFloat()
        val visual =
            DashboardMetricScalePreparer.score(
                value,
                0f,
                100f,
                listOf(
                    RawMetricBand(0f, 50f, MetricStatus.POOR),
                    RawMetricBand(50f, 75f, MetricStatus.WARNING),
                    RawMetricBand(75f, 100f, MetricStatus.OPTIMAL),
                ),
            )
        val title = resourceProvider.getString(DashboardR.string.card_title_ras_daily)
        val valueText = metrics?.rasRounded?.toString() ?: "—"
        val status = value.rasStatus()

        return DashboardMetricPresentation(
            title = title,
            valueText = valueText,
            unitText = "",
            secondaryText = null,
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
        summary: DailySummary?,
        metrics: DailyMetrics?,
        prefs: UserPreferences,
    ) = run {
        val baseline = metrics?.rhrBaselineRaw
        val poorRatio = prefs.rhrWarningThreshold + (prefs.rhrWarningThreshold - 1f)
        DashboardMetricScalePreparer.personalBaseline(
            value = summary?.restingHeartRate?.toFloat(),
            baseline = baseline,
            axisMinimumRatio = 1f - (poorRatio - 1f),
            axisMaximumRatio = poorRatio,
            bands = rhrBands(baseline, poorRatio, prefs),
            baselineReady = summary?.isCalibrating == false && baseline != null && baseline > 0f,
        )
    }

    private fun hrvBands(
        baseline: Float?,
        poorRatio: Float,
        prefs: UserPreferences,
    ): List<RawMetricBand> =
        baseline?.let {
            val warning = prefs.hrvWarningThreshold
            val optimal = prefs.hrvOptimalThreshold
            listOf(
                RawMetricBand(it * poorRatio, it * warning, MetricStatus.WARNING),
                RawMetricBand(it * warning, it * optimal, MetricStatus.NEUTRAL),
                RawMetricBand(it * optimal, it * (1f + (1f - poorRatio)), MetricStatus.OPTIMAL),
            )
        } ?: emptyList()

    private fun rhrBands(
        baseline: Float?,
        poorRatio: Float,
        prefs: UserPreferences,
    ): List<RawMetricBand> =
        baseline?.let {
            val optimal = prefs.rhrOptimalThreshold
            val warning = prefs.rhrWarningThreshold
            listOf(
                RawMetricBand(it * (1f - (poorRatio - 1f)), it * optimal, MetricStatus.OPTIMAL),
                RawMetricBand(it * optimal, it * warning, MetricStatus.NEUTRAL),
                RawMetricBand(it * warning, it * poorRatio, MetricStatus.WARNING),
            )
        } ?: emptyList()

    private fun personalBaselineDescription(
        title: String,
        valueText: String,
        ratio: Float?,
        optimalThreshold: Float,
        warningThreshold: Float,
        higherIsBetter: Boolean,
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
            DashboardR.string.semantics_value_note_format,
            title,
            valueText,
            resourceProvider.getString(relation),
        )
    }

    private fun hrvTooltip(metrics: DailyMetrics?): String =
        buildString {
            append(resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv))
            val baseline = metrics?.hrvBaselineRounded
            val arrow = metrics?.hrvBaselineArrow?.symbol
            val difference = metrics?.hrvBaselineDiff
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
        metrics: DailyMetrics?,
        isSleep: Boolean,
    ): String {
        val baseline = metrics?.rhrBaselineRounded
        val arrow = metrics?.rhrBaselineArrow?.symbol
        val difference = metrics?.rhrBaselineDiff
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
        reason: DashboardMetricUnavailableReason,
    ): String =
        resourceProvider.getString(
            DashboardR.string.semantics_unavailable_format,
            title,
            resourceProvider.getString(
                when (reason) {
                    DashboardMetricUnavailableReason.MISSING_VALUE ->
                        CoreUiR.string.metric_unavailable_missing_value
                    DashboardMetricUnavailableReason.MISSING_TARGET ->
                        CoreUiR.string.metric_unavailable_missing_target
                    DashboardMetricUnavailableReason.BASELINE_NOT_READY ->
                        CoreUiR.string.metric_unavailable_baseline_not_ready
                    DashboardMetricUnavailableReason.MISSING_BMI ->
                        CoreUiR.string.metric_unavailable_missing_bmi
                },
            ),
        )
}
