package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.HeartRateStatusClassifier
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.service.HealthMetricsService
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
                value < 40f -> MetricStatus.POOR
                value < 60f -> MetricStatus.WARNING
                value < 85f -> MetricStatus.NEUTRAL
                else -> MetricStatus.OPTIMAL
            }

        private fun sleepEfficiencyStatus(value: Float?): MetricStatus =
            when {
                value == null -> MetricStatus.CALIBRATING
                value < 70f -> MetricStatus.POOR
                value < 80f -> MetricStatus.WARNING
                value < 85f -> MetricStatus.NEUTRAL
                else -> MetricStatus.OPTIMAL
            }

        private fun spo2Status(value: Float?): MetricStatus =
            when {
                value == null -> MetricStatus.CALIBRATING
                value < 90f -> MetricStatus.POOR
                value < 95f -> MetricStatus.WARNING
                value < 98f -> MetricStatus.NEUTRAL
                else -> MetricStatus.OPTIMAL
            }

        private fun circadianStatus(value: Float?): MetricStatus =
            when {
                value == null -> MetricStatus.CALIBRATING
                value < 40f -> MetricStatus.POOR
                value < 60f -> MetricStatus.WARNING
                value < 80f -> MetricStatus.NEUTRAL
                else -> MetricStatus.OPTIMAL
            }

        private fun strainStatus(value: Float?): MetricStatus =
            when {
                value == null -> MetricStatus.CALIBRATING
                value < 0.5f -> MetricStatus.POOR
                value < 0.8f -> MetricStatus.WARNING
                value < 1.3f -> MetricStatus.OPTIMAL
                value < 1.5f -> MetricStatus.WARNING
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

            val unavailableValueText =
                resourceProvider.getString(CoreUiR.string.metric_value_unavailable)
            val scoreMaximumText =
                resourceProvider.getString(CoreUiR.string.score_maximum)

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
            val sleepScoreValueText = m?.sleepScoreRounded?.toString() ?: unavailableValueText
            val sleepScoreDescription =
                sleepScoreVisual.unavailableReason?.let { reason ->
                    unavailableDescription(sleepScoreTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    sleepScoreTitle,
                    sleepScoreValueText,
                    scoreMaximumText,
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
            val readinessScore =
                summary?.let {
                    LoadSourceSelector.selectReadiness(it, preferences.strainLoadSourceMode)
                }
            val readinessVisual = DashboardMetricScalePreparer.score(readinessScore, 0f, 100f)
            val readinessStatus = scoreStatus(readinessScore)
            val readinessTitle = resourceProvider.getString(CoreUiR.string.card_title_readiness)
            val readinessValueText = m?.readinessRounded?.toString() ?: unavailableValueText
            val readinessDescription =
                readinessVisual.unavailableReason?.let { reason ->
                    unavailableDescription(readinessTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    readinessTitle,
                    readinessValueText,
                    scoreMaximumText,
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
            val bmiAssessment = bmi?.let(BodyCompositionAssessment::assessBmi)
            val bmiReference = BodyCompositionAssessment.bmiReference
            val weightVisual =
                DashboardMetricScalePreparer.referenceRange(
                    value = bmi,
                    minimum = bmiReference.axisMinimum,
                    midpoint = bmiReference.referenceMidpoint,
                    maximum = bmiReference.axisMaximum,
                    scaleAvailable = isHeightValid,
                    unavailableReason = if (!isHeightValid) DashboardMetricUnavailableReason.MISSING_BMI else null,
                )
            val weightStatus = bmiAssessment?.status?.toMetricStatus() ?: MetricStatus.CALIBRATING
            val weightTitle =
                resourceProvider.getString(DashboardR.string.card_title_weight)
            val weightValueText =
                summary?.weightKg?.let {
                    MetricFormatter.formatWeightNumericOnly(it, preferences.unitSystem)
                } ?: unavailableValueText
            val weightUnitText =
                resourceProvider.getString(
                    if (preferences.unitSystem == UnitSystem.METRIC) {
                        CoreUiR.string.unit_kg
                    } else {
                        CoreUiR.string.unit_lbs
                    },
                )
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
                    DashboardR.string.semantics_value_note_format,
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
            val bodyFatAssessment =
                bodyFatPercent?.let {
                    BodyCompositionAssessment.assessBodyFat(
                        bodyFatPercent = it,
                        physiologyProfile = preferences.physiologyProfile,
                        gender = preferences.gender,
                    )
                }
            val bodyFatStatusVal = bodyFatAssessment?.status?.toMetricStatus() ?: MetricStatus.NEUTRAL
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
                resourceProvider.getString(DashboardR.string.card_title_body_fat)
            // Percent baked into the main value text (like Sleep Efficiency) so the "%" renders
            // at the value's size instead of as a small separate unit. Unlike Sleep Efficiency,
            // body fat keeps its one decimal place, via the shared formatter the vitals feature
            // already uses.
            val bodyFatValueText = bodyFatPercent?.let { MetricFormatter.formatBodyFat(it) } ?: unavailableValueText
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
                    DashboardR.string.semantics_value_note_format,
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
            val effStatus = sleepEfficiencyStatus(efficiencyPercent)

            val effValText =
                if (efficiencyPercent == null) {
                    unavailableValueText
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
                    CoreUiR.string.card_title_sleep_efficiency,
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
            val oxygenStatus = spo2Status(spo2)
            val spo2Visual =
                DashboardMetricScalePreparer.score(
                    spo2,
                    80f,
                    100f,
                )
            val spo2Title =
                resourceProvider.getString(
                    DashboardR.string.card_title_oxygen_saturation,
                )
            // Percent baked into the main value text (like Sleep Efficiency) so the "%" renders
            // at the value's size instead of as a small separate unit.
            val spo2ValueText = roundedSpo2?.let { "$it%" } ?: unavailableValueText
            val spo2Description =
                spo2Visual.unavailableReason?.let { reason ->
                    unavailableDescription(spo2Title, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    spo2Title,
                    spo2ValueText,
                    classificationText(oxygenStatus),
                )
            map[CardId.OXYGEN_SATURATION] =
                DashboardMetricPresentation(
                    title = spo2Title,
                    valueText = spo2ValueText,
                    unitText = "",
                    secondaryText = null,
                    status = oxygenStatus,
                    tooltip = resourceProvider.getString(CoreUiR.string.tooltip_vitals_spo2),
                    accessibilityDescription = spo2Description,
                    visual = spo2Visual,
                )

            // 12. BLOOD PRESSURE
            val systolic = summary?.bloodPressureSystolic ?: 0
            val diastolic = summary?.bloodPressureDiastolic ?: 0
            val bpStatus =
                if (systolic > 0 && diastolic > 0) {
                    HealthMetricsService().assessBloodPressure(systolic, diastolic).toMetricStatus()
                } else {
                    MetricStatus.NEUTRAL
                }
            val bpTitle =
                resourceProvider.getString(
                    DashboardR.string.card_title_blood_pressure,
                )
            val bpValueText =
                m?.bloodPressureDisplay
                    ?: if (systolic > 0 && diastolic > 0) "$systolic/$diastolic" else unavailableValueText
            val bpUnitText = resourceProvider.getString(CoreUiR.string.unit_mmHg)
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
                    DashboardR.string.card_title_heart_rate,
                )
            val hrValueText = heartRateSummary?.let { "${it.minBpm}–${it.maxBpm}" } ?: unavailableValueText
            val hrSecondaryText =
                heartRateSummary?.let {
                    resourceProvider.getString(CoreUiR.string.hr_avg_display, it.avgBpm)
                }
            val hrStatus = HeartRateStatusClassifier.classify(heartRateSummary?.avgBpm)
            val hrDescription =
                if (heartRateSummary?.avgBpm == null) {
                    unavailableDescription(hrTitle, DashboardMetricUnavailableReason.MISSING_VALUE)
                } else {
                    resourceProvider.getString(
                        DashboardR.string.semantics_value_note_format,
                        hrTitle,
                        "$hrValueText $hrSecondaryText",
                        classificationText(hrStatus),
                    )
                }
            map[CardId.HEART_RATE] =
                DashboardMetricPresentation(
                    title = hrTitle,
                    valueText = hrValueText,
                    unitText = "",
                    secondaryText = hrSecondaryText,
                    status = hrStatus,
                    tooltip = resourceProvider.getString(DashboardR.string.tooltip_heart_rate_card),
                    accessibilityDescription = hrDescription,
                    visual = DashboardMetricVisual.ValueOnly,
                )

            // 14. CIRCADIAN
            val circReady = circadianResult as? app.readylytics.health.domain.scoring.CircadianConsistencyResult.Ready
            val circTitle =
                resourceProvider.getString(
                    DashboardR.string.card_title_circadian_consistency,
                )
            val circSemanticsValueText = circReady?.score?.roundToInt()?.toString() ?: unavailableValueText
            val circValueText = circReady?.score?.roundToInt()?.let { "$it%" } ?: unavailableValueText
            val circVisual =
                DashboardMetricScalePreparer.score(
                    circReady?.score,
                    0f,
                    100f,
                )
            val circStatus = circadianStatus(circReady?.score)
            val circadianDescription =
                circVisual.unavailableReason?.let { reason ->
                    unavailableDescription(circTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    circTitle,
                    circSemanticsValueText,
                    scoreMaximumText,
                    classificationText(circStatus),
                )
            map[CardId.CIRCADIAN_CONSISTENCY] =
                DashboardMetricPresentation(
                    title = circTitle,
                    valueText = circValueText,
                    unitText = "",
                    secondaryText = null,
                    status = circStatus,
                    tooltip = resourceProvider.getString(CoreUiR.string.tooltip_circadian_score),
                    accessibilityDescription = circadianDescription,
                    visual = circVisual,
                )

            // 15. STRAIN RATIO
            val strainTitle =
                resourceProvider.getString(
                    CoreUiR.string.card_title_strain_ratio,
                )
            val strainValueText = m?.strainRatioDisplay ?: unavailableValueText
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
            val strainStatus = strainStatus(m?.strainRatioRaw)
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
