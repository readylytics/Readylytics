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
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.feature.dashboard.DashboardMetricPresentation
import app.readylytics.health.feature.dashboard.DashboardMetricScalePreparer
import app.readylytics.health.feature.dashboard.DashboardMetricUnavailableReason
import app.readylytics.health.feature.dashboard.DashboardMetricVisual
import app.readylytics.health.feature.dashboard.RawMetricBand
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

            val scoreBands =
                listOf(
                    RawMetricBand(0f, 40f, MetricStatus.POOR),
                    RawMetricBand(40f, 60f, MetricStatus.WARNING),
                    RawMetricBand(60f, 85f, MetricStatus.NEUTRAL),
                    RawMetricBand(85f, 100f, MetricStatus.OPTIMAL),
                )

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
                    scoreBands,
                )
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
                    classificationText(sleepScoreVisual.getResolvedStatus()),
                )
            map[CardId.SLEEP_SCORE] =
                DashboardMetricPresentation(
                    title = sleepScoreTitle,
                    valueText = sleepScoreValueText,
                    unitText = "",
                    secondaryText = null,
                    status = sleepScoreVisual.getResolvedStatus(),
                    tooltip = "",
                    accessibilityDescription = sleepScoreDescription,
                    visual = sleepScoreVisual,
                )

            // 2. READINESS
            val readinessScore = m?.readinessRounded?.toFloat()
            val readinessVisual = DashboardMetricScalePreparer.score(readinessScore, 0f, 100f, scoreBands)
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
                    classificationText(readinessVisual.getResolvedStatus()),
                )
            map[CardId.READINESS] =
                DashboardMetricPresentation(
                    title = readinessTitle,
                    valueText = readinessValueText,
                    unitText = "",
                    secondaryText = null,
                    status = readinessVisual.getResolvedStatus(),
                    tooltip = "",
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
                    bands =
                        listOf(
                            RawMetricBand(0f, 18.5f, MetricStatus.WARNING),
                            RawMetricBand(18.5f, 25f, MetricStatus.OPTIMAL),
                            RawMetricBand(25f, 30f, MetricStatus.WARNING),
                            RawMetricBand(30f, 100f, MetricStatus.POOR),
                        ),
                    scaleAvailable = isHeightValid,
                    unavailableReason = if (!isHeightValid) DashboardMetricUnavailableReason.MISSING_BMI else null,
                )
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
            val weightDescription =
                weightVisual.unavailableReason?.let { reason ->
                    unavailableDescription(weightTitle, reason)
                } ?: resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.semantics_value_note_format,
                    weightTitle,
                    "$weightValueText $weightUnitText",
                    classificationText(weightVisual.getResolvedStatus()),
                )
            map[CardId.WEIGHT] =
                DashboardMetricPresentation(
                    title = weightTitle,
                    valueText = weightValueText,
                    unitText = weightUnitText,
                    secondaryText = null,
                    status = weightVisual.getResolvedStatus(),
                    tooltip = "",
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
                    bands = emptyList(),
                    scaleAvailable = true,
                    unavailableReason = null,
                )
            val bodyFatTitle =
                resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_body_fat)
            val bodyFatValueText = bodyFatPercent?.toString() ?: "—"
            val bodyFatDescription =
                bodyFatVisual.unavailableReason?.let { reason ->
                    unavailableDescription(bodyFatTitle, reason)
                } ?: resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.semantics_value_note_format,
                    bodyFatTitle,
                    "$bodyFatValueText%",
                    classificationText(bodyFatStatusVal),
                )
            map[CardId.BODY_FAT] =
                DashboardMetricPresentation(
                    title = bodyFatTitle,
                    valueText = bodyFatValueText,
                    unitText = "%",
                    secondaryText = null,
                    status = bodyFatStatusVal,
                    tooltip = "",
                    accessibilityDescription = bodyFatDescription,
                    visual = bodyFatVisual,
                )

            // 10. SLEEP EFFICIENCY
            val efficiency = lastSleepSession?.efficiency
            val effStatus =
                if (efficiency != null) {
                    when {
                        efficiency >= 85f -> MetricStatus.OPTIMAL
                        efficiency >= 75f -> MetricStatus.NEUTRAL
                        efficiency >= 65f -> MetricStatus.WARNING
                        else -> MetricStatus.POOR
                    }
                } else {
                    MetricStatus.NEUTRAL
                }

            val effValText =
                if (efficiency == null) {
                    "—"
                } else if (efficiency == 0f) {
                    "0"
                } else {
                    efficiency.roundToInt().toString()
                }
            val effVisual =
                DashboardMetricScalePreparer.score(
                    efficiency,
                    0f,
                    100f,
                    listOf(
                        RawMetricBand(0f, 70f, MetricStatus.POOR),
                        RawMetricBand(70f, 80f, MetricStatus.WARNING),
                        RawMetricBand(80f, 85f, MetricStatus.NEUTRAL),
                        RawMetricBand(85f, 100f, MetricStatus.OPTIMAL),
                    ),
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
                    "$effValText%",
                    classificationText(effVisual.getResolvedStatus()),
                )
            map[CardId.SLEEP_EFFICIENCY] =
                DashboardMetricPresentation(
                    title = sleepEffTitle,
                    valueText = effValText,
                    unitText = "%",
                    secondaryText = null,
                    status = effStatus,
                    tooltip = "",
                    accessibilityDescription = sleepEffDescription,
                    visual = effVisual,
                )

            // 11. OXYGEN SATURATION
            val spo2 = summary?.avgSleepingSpo2
            val roundedSpo2 = spo2?.roundToInt()
            val spo2Status =
                when {
                    roundedSpo2 == null -> MetricStatus.CALIBRATING
                    roundedSpo2 >= 95 -> MetricStatus.OPTIMAL
                    roundedSpo2 >= 90 -> MetricStatus.WARNING
                    else -> MetricStatus.POOR
                }
            val spo2Visual =
                DashboardMetricScalePreparer.score(
                    spo2,
                    80f,
                    100f,
                    listOf(
                        RawMetricBand(80f, 90f, MetricStatus.POOR),
                        RawMetricBand(90f, 95f, MetricStatus.WARNING),
                        RawMetricBand(95f, 98f, MetricStatus.NEUTRAL),
                        RawMetricBand(98f, 100f, MetricStatus.OPTIMAL),
                    ),
                )
            val spo2Title =
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.card_title_oxygen_saturation,
                )
            val spo2ValueText = roundedSpo2?.toString() ?: "—"
            val spo2UnitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_percent)
            val spo2Description =
                spo2Visual.unavailableReason?.let { reason ->
                    unavailableDescription(spo2Title, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    spo2Title,
                    "$spo2ValueText$spo2UnitText",
                    classificationText(spo2Visual.getResolvedStatus()),
                )
            map[CardId.OXYGEN_SATURATION] =
                DashboardMetricPresentation(
                    title = spo2Title,
                    valueText = spo2ValueText,
                    unitText = spo2UnitText,
                    secondaryText = null,
                    status = spo2Visual.getResolvedStatus(),
                    tooltip = "",
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
                    tooltip = "",
                    accessibilityDescription = bpDescription,
                    visual = DashboardMetricVisual.ValueOnly,
                )

            // 13. HEART RATE
            val hrTitle =
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.card_title_heart_rate,
                )
            val hrValueText = heartRateSummary?.avgBpm?.toString() ?: "—"
            val hrUnitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_bpm)
            val hrDescription =
                if (heartRateSummary?.avgBpm == null) {
                    unavailableDescription(hrTitle, DashboardMetricUnavailableReason.MISSING_VALUE)
                } else {
                    resourceProvider.getString(
                        DashboardR.string.semantics_value_note_format,
                        hrTitle,
                        "$hrValueText $hrUnitText",
                        classificationText(MetricStatus.NEUTRAL),
                    )
                }
            map[CardId.HEART_RATE] =
                DashboardMetricPresentation(
                    title = hrTitle,
                    valueText = hrValueText,
                    unitText = hrUnitText,
                    secondaryText = null,
                    status = MetricStatus.NEUTRAL,
                    tooltip = "",
                    accessibilityDescription = hrDescription,
                    visual = DashboardMetricVisual.ValueOnly,
                )

            // 14. CIRCADIAN
            val circReady = circadianResult as? app.readylytics.health.domain.scoring.CircadianConsistencyResult.Ready
            val circTitle =
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.card_title_circadian_consistency,
                )
            val circValueText = circReady?.score?.roundToInt()?.toString() ?: "—"
            val circVisual =
                DashboardMetricScalePreparer.score(
                    circReady?.score,
                    0f,
                    100f,
                    listOf(
                        RawMetricBand(0f, 40f, MetricStatus.POOR),
                        RawMetricBand(40f, 60f, MetricStatus.WARNING),
                        RawMetricBand(60f, 80f, MetricStatus.NEUTRAL),
                        RawMetricBand(80f, 100f, MetricStatus.OPTIMAL),
                    ),
                )
            val circadianDescription =
                circVisual.unavailableReason?.let { reason ->
                    unavailableDescription(circTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    circTitle,
                    circValueText,
                    "100",
                    classificationText(circVisual.getResolvedStatus()),
                )
            map[CardId.CIRCADIAN_CONSISTENCY] =
                DashboardMetricPresentation(
                    title = circTitle,
                    valueText = circValueText,
                    unitText = "",
                    secondaryText = null,
                    status = MetricStatus.NEUTRAL,
                    tooltip = "",
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
                    listOf(
                        RawMetricBand(0f, 0.5f, MetricStatus.POOR),
                        RawMetricBand(0.5f, 0.8f, MetricStatus.WARNING),
                        RawMetricBand(0.8f, 1.3f, MetricStatus.OPTIMAL),
                        RawMetricBand(1.3f, 1.5f, MetricStatus.WARNING),
                        RawMetricBand(1.5f, 2.0f, MetricStatus.POOR),
                    ),
                )
            val strainDescription =
                strainVisual.unavailableReason?.let { reason ->
                    unavailableDescription(strainTitle, reason)
                } ?: resourceProvider.getString(
                    DashboardR.string.semantics_value_note_format,
                    strainTitle,
                    strainValueText,
                    classificationText(strainVisual.getResolvedStatus()),
                )
            map[CardId.STRAIN_RATIO] =
                DashboardMetricPresentation(
                    title = strainTitle,
                    valueText = strainValueText,
                    unitText = "",
                    secondaryText = strainIncreaseText,
                    status = MetricStatus.NEUTRAL,
                    tooltip = "",
                    accessibilityDescription = strainDescription,
                    visual = strainVisual,
                )

            return map
        }
    }
