package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.domain.calculation.HealthMetricsCalculator
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.model.strainRatioStatus
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.toStatus
import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.feature.dashboard.DashboardMetricPresentation
import app.readylytics.health.feature.dashboard.DashboardMetricScalePreparer
import app.readylytics.health.feature.dashboard.DashboardMetricUnavailableReason
import app.readylytics.health.feature.dashboard.DashboardMetricVisual
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardMetricPresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
        private val getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase,
    ) {
        // Human-readable, TalkBack-friendly accessibilityDescription wiring for all 15 dashboard metric
        // cards (Sleep Score, Readiness, Weight, Body Fat, Sleep Duration, HRV, Sleep RHR, Resting HR,
        // RAS Daily, Sleep Efficiency, Oxygen Saturation, Blood Pressure, Heart Rate, Circadian Consistency,
        // and Strain Ratio).

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

        private fun unavailableReasonText(reason: DashboardMetricUnavailableReason): String =
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
            )

        private fun unavailableDescription(
            title: String,
            reason: DashboardMetricUnavailableReason,
        ): String =
            resourceProvider.getString(
                DashboardR.string.semantics_unavailable_format,
                title,
                unavailableReasonText(reason),
            )

        private fun scoreStatus(value: Float?): MetricStatus =
            when {
                value == null -> MetricStatus.CALIBRATING
                value >= 85f -> MetricStatus.OPTIMAL
                value >= 60f -> MetricStatus.NEUTRAL
                value >= 40f -> MetricStatus.WARNING
                else -> MetricStatus.POOR
            }

        fun build(
            summary: DailySummary?,
            preferences: UserPreferences,
            selectedDate: LocalDate,
            lastSleepSession: SleepSessionSummary?,
            circadianResult: CircadianConsistencyResult?,
            heartRateSummary: HeartRateDaySummary?,
            todayStrainIncrease: Float? = null,
        ): Map<CardId, DashboardMetricPresentation> {
            val map = mutableMapOf<CardId, DashboardMetricPresentation>()

            val m = if (summary != null) DailyMetricsMapper.toMetrics(summary, preferences) else null

            map.putAll(
                DashboardRecoveryMetricPresentationFactory(resourceProvider).build(
                    summary = summary,
                    metrics = m,
                    preferences = preferences,
                    lastSleepSession = lastSleepSession,
                ),
            )

            // 1. SLEEP SCORE
            val sleepScoreVisual =
                DashboardMetricScalePreparer.score(
                    summary?.sleepScore,
                    0f,
                    100f,
                )
            val sleepScoreStatus = scoreStatus(summary?.sleepScore)
            val sleepScoreTitle =
                resourceProvider.getString(DashboardR.string.card_title_sleep_score)
            val sleepScoreValueText = m?.sleepScoreRounded?.toString() ?: "—"
            val sleepScoreDescription =
                sleepScoreVisual.unavailableReason?.let { reason ->
                    unavailableDescription(sleepScoreTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    sleepScoreTitle,
                    sleepScoreValueText,
                    "100",
                    classificationText(sleepScoreStatus),
                )
            map[CardId.SLEEP_SCORE] =
                DashboardMetricPresentation(
                    title = sleepScoreTitle,
                    valueText = sleepScoreValueText,
                    unitText = "",
                    secondaryText = null,
                    status = sleepScoreStatus,
                    tooltip = resourceProvider.getString(CoreUiR.string.tooltip_sleep_score),
                    accessibilityDescription = sleepScoreDescription,
                    visual = sleepScoreVisual,
                )

            // 2. READINESS
            val readinessScore = m?.readinessRounded?.toFloat()
            val readinessVisual = DashboardMetricScalePreparer.score(readinessScore, 0f, 100f)
            val readinessStatus = scoreStatus(readinessScore)
            val readinessTitle = resourceProvider.getString(CoreUiR.string.card_title_readiness)
            val readinessValueText = m?.readinessRounded?.toString() ?: "—"
            val readinessDescription =
                readinessVisual.unavailableReason?.let { reason ->
                    unavailableDescription(readinessTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    readinessTitle,
                    readinessValueText,
                    "100",
                    classificationText(readinessStatus),
                )
            map[CardId.READINESS] =
                DashboardMetricPresentation(
                    title = readinessTitle,
                    valueText = readinessValueText,
                    unitText = "",
                    secondaryText = null,
                    status = readinessStatus,
                    tooltip = resourceProvider.getString(CoreUiR.string.tooltip_readiness),
                    accessibilityDescription = readinessDescription,
                    visual = readinessVisual,
                )

            // 3. WEIGHT
            val heightM = (preferences.heightCm ?: 0f) / 100f
            val isHeightValid = heightM > 0f
            val bmi = if (isHeightValid) summary?.weightKg?.let { it / (heightM * heightM) } else null
            val weightVisual =
                DashboardMetricScalePreparer.referenceRange(
                    value = bmi,
                    minimum = 15f,
                    midpoint = 21.7f,
                    maximum = 35f,
                    scaleAvailable = isHeightValid,
                    unavailableReason = if (!isHeightValid) DashboardMetricUnavailableReason.MISSING_BMI else null,
                )
            val weightStatus =
                bmi?.let { HealthMetricsCalculator.assessBmi(it).toMetricStatus() }
                    ?: MetricStatus.CALIBRATING
            val weightTitle =
                resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_weight)
            val weightValueText = m?.weightKgDisplay?.replace(" kg", "")?.replace(" lbs", "") ?: "—"
            val weightUnitText =
                if (preferences.unitSystem ==
                    app.readylytics.health.domain.preferences.UnitSystem.METRIC
                ) {
                    "kg"
                } else {
                    "lbs"
                }
            // Same data-presence branching as DashboardRecoveryMetricPresentationFactory's
            // hrvTooltip/rhrTooltip: point at Health Connect when there is nothing to explain yet.
            val weightTooltip =
                if (summary?.weightKg == null) {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_weight_no_data)
                } else {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_weight_latest)
                }
            val weightDescription =
                weightVisual.unavailableReason?.let { reason ->
                    unavailableDescription(weightTitle, reason)
                } ?: resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.semantics_value_note_format,
                    weightTitle,
                    "$weightValueText $weightUnitText",
                    classificationText(weightStatus),
                )
            map[CardId.WEIGHT] =
                DashboardMetricPresentation(
                    title = weightTitle,
                    valueText = weightValueText,
                    unitText = weightUnitText,
                    secondaryText = null,
                    status = weightStatus,
                    tooltip = weightTooltip,
                    accessibilityDescription = weightDescription,
                    visual = weightVisual,
                )

            // 4. BODY FAT
            val bodyFatPercent = summary?.bodyFatPercent
            val bodyFatMidpoint =
                BodyCompositionAssessment
                    .assessBodyFat(
                        bodyFatPercent ?: 20f,
                        preferences.physiologyProfile,
                        preferences.gender,
                    ).reference.referenceMidpoint
            val bodyFatStatusVal =
                if (bodyFatPercent != null) {
                    HealthMetricsCalculator
                        .assessBodyFatPercent(
                            bodyFatPercent,
                            preferences.physiologyProfile,
                            preferences.gender,
                        ).toMetricStatus()
                } else {
                    MetricStatus.NEUTRAL
                }
            val bodyFatAssessment =
                bodyFatPercent?.let {
                    app.readylytics.health.domain.model.BodyCompositionAssessment.assessBodyFat(
                        it,
                        preferences.physiologyProfile,
                        preferences.gender,
                    )
                }
            val bodyFatVisual =
                DashboardMetricScalePreparer.referenceRange(
                    value = bodyFatPercent,
                    minimum = bodyFatAssessment?.reference?.axisMinimum ?: 0f,
                    midpoint = bodyFatAssessment?.reference?.referenceMidpoint ?: 20f,
                    maximum = bodyFatAssessment?.reference?.axisMaximum ?: 40f,
                    scaleAvailable = true,
                    unavailableReason = null,
                )
            val bodyFatTitle =
                resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_body_fat)
            // Percent baked into the main value text (like Sleep Efficiency) so the "%" renders
            // at the value's size instead of as a small separate unit. Unlike Sleep Efficiency,
            // body fat keeps its one decimal place, via the shared formatter the vitals feature
            // already uses.
            val bodyFatValueText = bodyFatPercent?.let { MetricFormatter.formatBodyFat(it) } ?: "—"
            val bodyFatTooltip =
                if (bodyFatPercent == null) {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_body_fat_no_data)
                } else {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_body_fat_latest)
                }
            val bodyFatDescription =
                bodyFatVisual.unavailableReason?.let { reason ->
                    unavailableDescription(bodyFatTitle, reason)
                } ?: resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.semantics_value_note_format,
                    bodyFatTitle,
                    bodyFatValueText,
                    classificationText(bodyFatStatusVal),
                )
            map[CardId.BODY_FAT] =
                DashboardMetricPresentation(
                    title = bodyFatTitle,
                    valueText = bodyFatValueText,
                    unitText = "",
                    secondaryText = null,
                    status = bodyFatStatusVal,
                    tooltip = bodyFatTooltip,
                    accessibilityDescription = bodyFatDescription,
                    visual = bodyFatVisual,
                )

            // 10. SLEEP EFFICIENCY
            val efficiency = lastSleepSession?.efficiency
            val efficiencyPercent =
                efficiency?.let { value ->
                    if (value in 0f..1f) value * 100f else value
                }
            val effStatus =
                if (efficiencyPercent != null) {
                    when {
                        efficiencyPercent >= 85f -> MetricStatus.OPTIMAL
                        efficiencyPercent >= 75f -> MetricStatus.NEUTRAL
                        efficiencyPercent >= 65f -> MetricStatus.WARNING
                        else -> MetricStatus.POOR
                    }
                } else {
                    MetricStatus.NEUTRAL
                }

            val effValText =
                if (efficiencyPercent == null) {
                    "—"
                } else if (efficiencyPercent == 0f) {
                    "0%"
                } else {
                    "${efficiencyPercent.roundToInt()}%"
                }
            val effVisual =
                DashboardMetricScalePreparer.score(
                    efficiencyPercent,
                    0f,
                    100f,
                )
            val sleepEffTitle =
                resourceProvider.getString(
                    app.readylytics.health.core.ui.R.string.card_title_sleep_efficiency,
                )
            val sleepEffDescription =
                effVisual.unavailableReason?.let { reason ->
                    unavailableDescription(sleepEffTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    sleepEffTitle,
                    effValText,
                    classificationText(effStatus),
                )
            map[CardId.SLEEP_EFFICIENCY] =
                DashboardMetricPresentation(
                    title = sleepEffTitle,
                    valueText = effValText,
                    unitText = "",
                    secondaryText = null,
                    status = effStatus,
                    tooltip = resourceProvider.getString(CoreUiR.string.card_tooltip_sleep_efficiency),
                    accessibilityDescription = sleepEffDescription,
                    visual = effVisual,
                )

            // 11. OXYGEN SATURATION
            val spo2 = summary?.avgSleepingSpo2
            val roundedSpo2 = spo2?.roundToInt()
            val spo2Status =
                when {
                    spo2 == null -> MetricStatus.CALIBRATING
                    spo2 >= 98f -> MetricStatus.OPTIMAL
                    spo2 >= 95f -> MetricStatus.NEUTRAL
                    spo2 >= 90f -> MetricStatus.WARNING
                    else -> MetricStatus.POOR
                }
            val spo2Visual =
                DashboardMetricScalePreparer.score(
                    spo2,
                    80f,
                    100f,
                )
            val spo2Title =
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.card_title_oxygen_saturation,
                )
            // Percent baked into the main value text (like Sleep Efficiency) so the "%" renders
            // at the value's size instead of as a small separate unit.
            val spo2ValueText = roundedSpo2?.let { "$it%" } ?: "—"
            val spo2Description =
                spo2Visual.unavailableReason?.let { reason ->
                    unavailableDescription(spo2Title, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    spo2Title,
                    spo2ValueText,
                    classificationText(spo2Status),
                )
            map[CardId.OXYGEN_SATURATION] =
                DashboardMetricPresentation(
                    title = spo2Title,
                    valueText = spo2ValueText,
                    unitText = "",
                    secondaryText = null,
                    status = spo2Status,
                    tooltip = resourceProvider.getString(CoreUiR.string.tooltip_vitals_spo2),
                    accessibilityDescription = spo2Description,
                    visual = spo2Visual,
                )

            // 12. BLOOD PRESSURE
            val systolic = summary?.bloodPressureSystolic ?: 0
            val diastolic = summary?.bloodPressureDiastolic ?: 0
            val bpStatus =
                if (systolic > 0 && diastolic > 0) {
                    HealthMetricsCalculator.assessBloodPressure(systolic, diastolic).toMetricStatus()
                } else {
                    MetricStatus.NEUTRAL
                }
            val bpTitle =
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.card_title_blood_pressure,
                )
            val bpValueText =
                m?.bloodPressureDisplay ?: if (systolic > 0 && diastolic > 0) "$systolic/$diastolic" else "—"
            val bpUnitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_mmHg)
            val bpTooltip =
                if (systolic <= 0 || diastolic <= 0) {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_bp_no_data)
                } else {
                    resourceProvider.getString(CoreUiR.string.card_tooltip_bp_latest)
                }
            val bpDescription =
                if (systolic <= 0 || diastolic <= 0) {
                    unavailableDescription(bpTitle, DashboardMetricUnavailableReason.MISSING_VALUE)
                } else {
                    resourceProvider.getString(
                        DashboardR.string.semantics_value_note_format,
                        bpTitle,
                        "$bpValueText $bpUnitText",
                        classificationText(bpStatus),
                    )
                }
            map[CardId.BLOOD_PRESSURE] =
                DashboardMetricPresentation(
                    title = bpTitle,
                    valueText = bpValueText,
                    unitText = bpUnitText,
                    secondaryText = null,
                    status = bpStatus,
                    tooltip = bpTooltip,
                    accessibilityDescription = bpDescription,
                    visual = DashboardMetricVisual.ValueOnly,
                )

            // 13. HEART RATE
            val hrTitle =
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.card_title_heart_rate,
                )
            val hrValueText = heartRateSummary?.let { "${it.minBpm}–${it.maxBpm}" } ?: "—"
            val hrSecondaryText =
                heartRateSummary?.let {
                    resourceProvider.getString(CoreUiR.string.hr_avg_display, it.avgBpm)
                }
            val hrDescription =
                if (heartRateSummary?.avgBpm == null) {
                    unavailableDescription(hrTitle, DashboardMetricUnavailableReason.MISSING_VALUE)
                } else {
                    resourceProvider.getString(
                        DashboardR.string.semantics_value_note_format,
                        hrTitle,
                        "$hrValueText $hrSecondaryText",
                        classificationText(MetricStatus.NEUTRAL),
                    )
                }
            map[CardId.HEART_RATE] =
                DashboardMetricPresentation(
                    title = hrTitle,
                    valueText = hrValueText,
                    unitText = "",
                    secondaryText = hrSecondaryText,
                    status = MetricStatus.NEUTRAL,
                    tooltip = resourceProvider.getString(DashboardR.string.tooltip_heart_rate_card),
                    accessibilityDescription = hrDescription,
                    visual = DashboardMetricVisual.ValueOnly,
                )

            // 14. CIRCADIAN
            val circReady = circadianResult as? app.readylytics.health.domain.scoring.CircadianConsistencyResult.Ready
            val circTitle =
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.card_title_circadian_consistency,
                )
            val circSemanticsValueText = circReady?.score?.roundToInt()?.toString() ?: "—"
            val circValueText = circReady?.score?.roundToInt()?.let { "$it%" } ?: "—"
            val circVisual =
                DashboardMetricScalePreparer.score(
                    circReady?.score,
                    0f,
                    100f,
                )
            val circadianStatus = circadianResult?.toStatus() ?: MetricStatus.CALIBRATING
            val circadianDescription =
                circVisual.unavailableReason?.let { reason ->
                    unavailableDescription(circTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    circTitle,
                    circSemanticsValueText,
                    "100",
                    classificationText(circadianStatus),
                )
            map[CardId.CIRCADIAN_CONSISTENCY] =
                DashboardMetricPresentation(
                    title = circTitle,
                    valueText = circValueText,
                    unitText = "",
                    secondaryText = null,
                    status = circadianStatus,
                    tooltip = resourceProvider.getString(CoreUiR.string.tooltip_circadian_score),
                    accessibilityDescription = circadianDescription,
                    visual = circVisual,
                )

            // 15. STRAIN RATIO
            val strainTitle =
                resourceProvider.getString(
                    app.readylytics.health.core.ui.R.string.card_title_strain_ratio,
                )
            val strainValueText = m?.strainRatioDisplay ?: "—"
            val strainIncreaseText =
                todayStrainIncrease?.let { increase ->
                    if (increase > 0.005f) {
                        resourceProvider.getString(
                            CoreUiR.string.delta_up_format,
                            resourceProvider.getString(CoreUiR.string.delta_up),
                            MetricFormatter.formatStrain(increase),
                        )
                    } else {
                        resourceProvider.getString(CoreUiR.string.delta_no_change)
                    }
                }
            val strainVisual =
                DashboardMetricScalePreparer.score(
                    m?.strainRatioRaw,
                    0f,
                    2f,
                )
            val strainStatus = m?.strainRatioRaw?.strainRatioStatus() ?: MetricStatus.CALIBRATING
            val strainDescription =
                strainVisual.unavailableReason?.let { reason ->
                    unavailableDescription(strainTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    strainTitle,
                    strainValueText,
                    classificationText(strainStatus),
                )
            map[CardId.STRAIN_RATIO] =
                DashboardMetricPresentation(
                    title = strainTitle,
                    valueText = strainValueText,
                    unitText = "",
                    secondaryText = strainIncreaseText,
                    status = strainStatus,
                    tooltip = resourceProvider.getString(CoreUiR.string.tooltip_strain_ratio),
                    accessibilityDescription = strainDescription,
                    visual = strainVisual,
                )

            return map
        }
    }
