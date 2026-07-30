package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.feature.dashboard.DashboardMetricPresentation
import app.readylytics.health.feature.dashboard.DashboardMetricScalePreparer
import app.readylytics.health.feature.dashboard.RawMetricBand
import app.readylytics.health.feature.dashboard.DashboardMetricUnavailableReason
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.calculation.HealthMetricsCalculator
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import app.readylytics.health.feature.dashboard.DashboardMetricVisual
import app.readylytics.health.domain.model.toMetricStatus
import java.util.Locale

class DashboardMetricPresentationFactory @Inject constructor(
    private val resourceProvider: ResourceProvider,
    private val getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase,
) {
    // Task 10: real accessibilityDescription wiring for the card types the accessibility
    // regression tests in DashboardMetricCardTest.kt cover (Sleep Score, Sleep Duration, HRV,
    // Weight, Body Fat, plus their Goal-unavailable/PersonalBaseline-not-ready branches). Every
    // other card below still passes an empty accessibilityDescription — see the Task 10 report
    // for that follow-up scope.

    private fun classificationText(status: MetricStatus): String = resourceProvider.getString(
        when (status) {
            MetricStatus.OPTIMAL -> app.readylytics.health.core.ui.R.string.metric_status_optimal
            MetricStatus.NEUTRAL -> app.readylytics.health.core.ui.R.string.metric_status_neutral
            MetricStatus.WARNING -> app.readylytics.health.core.ui.R.string.metric_status_warning
            MetricStatus.POOR -> app.readylytics.health.core.ui.R.string.metric_status_poor
            MetricStatus.NO_DATA,
            MetricStatus.CALIBRATING,
            -> app.readylytics.health.core.ui.R.string.metric_status_calibrating
        }
    )

    private fun unavailableReasonText(reason: DashboardMetricUnavailableReason): String = resourceProvider.getString(
        when (reason) {
            DashboardMetricUnavailableReason.MISSING_VALUE ->
                app.readylytics.health.core.ui.R.string.metric_unavailable_missing_value
            DashboardMetricUnavailableReason.MISSING_TARGET ->
                app.readylytics.health.core.ui.R.string.metric_unavailable_missing_target
            DashboardMetricUnavailableReason.BASELINE_NOT_READY ->
                app.readylytics.health.core.ui.R.string.metric_unavailable_baseline_not_ready
            DashboardMetricUnavailableReason.MISSING_BMI ->
                app.readylytics.health.core.ui.R.string.metric_unavailable_missing_bmi
        }
    )

    private fun unavailableDescription(title: String, reason: DashboardMetricUnavailableReason): String =
        resourceProvider.getString(
            app.readylytics.health.feature.dashboard.R.string.semantics_unavailable_format,
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
    ): Map<CardId, DashboardMetricPresentation> {
        val map = mutableMapOf<CardId, DashboardMetricPresentation>()
        
        val scoreBands = listOf(
            RawMetricBand(0f, 40f, MetricStatus.POOR),
            RawMetricBand(40f, 60f, MetricStatus.WARNING),
            RawMetricBand(60f, 85f, MetricStatus.NEUTRAL),
            RawMetricBand(85f, 100f, MetricStatus.OPTIMAL)
        )
        
        val m = if (summary != null) DailyMetricsMapper.toMetrics(summary, preferences) else null
        
        // 1. SLEEP SCORE
        val sleepScoreVisual = DashboardMetricScalePreparer.score(summary?.sleepScore?.toFloat(), 0f, 100f, scoreBands)
        val sleepScoreTitle =
            resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_sleep_score)
        val sleepScoreValueText = m?.sleepScoreRounded?.toString() ?: "—"
        val sleepScoreDescription = sleepScoreVisual.unavailableReason?.let { reason ->
            unavailableDescription(sleepScoreTitle, reason)
        } ?: resourceProvider.getString(
            app.readylytics.health.feature.dashboard.R.string.semantics_score_format,
            sleepScoreTitle,
            sleepScoreValueText,
            "100",
            classificationText(sleepScoreVisual.getResolvedStatus()),
        )
        map[CardId.SLEEP_SCORE] = DashboardMetricPresentation(
            title = sleepScoreTitle,
            valueText = sleepScoreValueText,
            unitText = "",
            secondaryText = null,
            status = sleepScoreVisual.getResolvedStatus(),
            tooltip = "",
            accessibilityDescription = sleepScoreDescription,
            visual = sleepScoreVisual
        )
        
        // 2. READINESS
        val readinessScore = m?.readinessRounded?.toFloat()
        val readinessVisual = DashboardMetricScalePreparer.score(readinessScore, 0f, 100f, scoreBands)
        map[CardId.READINESS] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.core.ui.R.string.card_title_readiness),
            valueText = m?.readinessRounded?.toString() ?: "—",
            unitText = "",
            secondaryText = null,
            status = readinessVisual.getResolvedStatus(),
            tooltip = "",
            accessibilityDescription = "",
            visual = readinessVisual
        )
        
        // 3. WEIGHT
        val heightM = (preferences.heightCm ?: 0f) / 100f
        val isHeightValid = heightM > 0f
        val bmi = if (isHeightValid) summary?.weightKg?.let { it / (heightM * heightM) } else null
        val weightVisual = DashboardMetricScalePreparer.referenceRange(
            value = bmi,
            minimum = 15f,
            midpoint = 21.7f,
            maximum = 35f,
            bands = listOf(
                RawMetricBand(0f, 18.5f, MetricStatus.WARNING),
                RawMetricBand(18.5f, 25f, MetricStatus.OPTIMAL),
                RawMetricBand(25f, 30f, MetricStatus.WARNING),
                RawMetricBand(30f, 100f, MetricStatus.POOR)
            ),
            scaleAvailable = isHeightValid,
            unavailableReason = if (!isHeightValid) DashboardMetricUnavailableReason.MISSING_BMI else null
        )
        val bmiStatus = bmi?.let { app.readylytics.health.domain.model.BodyCompositionAssessment.assessBmi(it).status }
        val categoryStr = bmiStatus?.let { status ->
            resourceProvider.getString(
                when (status) {
                    app.readylytics.health.domain.model.BmiStatus.Optimal -> app.readylytics.health.feature.dashboard.R.string.bmi_optimal
                    app.readylytics.health.domain.model.BmiStatus.Neutral -> app.readylytics.health.feature.dashboard.R.string.bmi_neutral
                    app.readylytics.health.domain.model.BmiStatus.Warning -> app.readylytics.health.feature.dashboard.R.string.bmi_warning
                    app.readylytics.health.domain.model.BmiStatus.Poor -> app.readylytics.health.feature.dashboard.R.string.bmi_poor
                }
            )
        }
        val bmiSecondary = if (bmi != null && categoryStr != null) {
            resourceProvider.getString(app.readylytics.health.core.ui.R.string.bmi_secondary_text, String.format(java.util.Locale.getDefault(), "%.1f", bmi), categoryStr)
        } else null
        
        val weightTitle =
            resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_weight)
        val weightValueText = m?.weightKgDisplay?.replace(" kg", "")?.replace(" lbs", "") ?: "—"
        val weightUnitText =
            if (preferences.unitSystem == app.readylytics.health.domain.preferences.UnitSystem.METRIC) "kg" else "lbs"
        val weightDescription = weightVisual.unavailableReason?.let { reason ->
            unavailableDescription(weightTitle, reason)
        } ?: resourceProvider.getString(
            app.readylytics.health.feature.dashboard.R.string.semantics_weight_bmi_format,
            weightTitle,
            "$weightValueText $weightUnitText",
            bmiSecondary ?: "",
            categoryStr ?: "",
        )
        map[CardId.WEIGHT] = DashboardMetricPresentation(
            title = weightTitle,
            valueText = weightValueText,
            unitText = weightUnitText,
            secondaryText = bmiSecondary,
            status = weightVisual.getResolvedStatus(),
            tooltip = "",
            accessibilityDescription = weightDescription,
            visual = weightVisual
        )
        
        // 4. BODY FAT
        val bodyFatPercent = summary?.bodyFatPercent
        val bodyFatMidpoint = BodyCompositionAssessment.assessBodyFat(bodyFatPercent ?: 20f, preferences.physiologyProfile, preferences.gender).reference.referenceMidpoint
        val bodyFatStatusVal = if (bodyFatPercent != null) {
            HealthMetricsCalculator.assessBodyFatPercent(bodyFatPercent, preferences.physiologyProfile, preferences.gender).toMetricStatus()
        } else {
            MetricStatus.NEUTRAL
        }
        val bodyFatAssessment = bodyFatPercent?.let { app.readylytics.health.domain.model.BodyCompositionAssessment.assessBodyFat(it, preferences.physiologyProfile, preferences.gender) }
        val bodyFatVisual = DashboardMetricScalePreparer.referenceRange(
            value = bodyFatPercent,
            minimum = bodyFatAssessment?.reference?.axisMinimum ?: 0f,
            midpoint = bodyFatAssessment?.reference?.referenceMidpoint ?: 20f,
            maximum = bodyFatAssessment?.reference?.axisMaximum ?: 40f,
            bands = emptyList(),
            scaleAvailable = true,
            unavailableReason = null
        )
        val bodyFatTitle =
            resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_body_fat)
        val bodyFatValueText = bodyFatPercent?.toString() ?: "—"
        val bodyFatDescription = bodyFatVisual.unavailableReason?.let { reason ->
            unavailableDescription(bodyFatTitle, reason)
        } ?: resourceProvider.getString(
            app.readylytics.health.feature.dashboard.R.string.semantics_value_note_format,
            bodyFatTitle,
            "$bodyFatValueText%",
            classificationText(bodyFatStatusVal),
        )
        map[CardId.BODY_FAT] = DashboardMetricPresentation(
            title = bodyFatTitle,
            valueText = bodyFatValueText,
            unitText = "%",
            secondaryText = null,
            status = bodyFatStatusVal,
            tooltip = "",
            accessibilityDescription = bodyFatDescription,
            visual = bodyFatVisual
        )
        
        // 5. SLEEP DURATION
        val sleepMins = summary?.sleepDurationMinutes
        val goalMins = (preferences.goalSleepHours * 60).toInt()
        val durationStatus = if (sleepMins != null) {
            if (sleepMins >= goalMins) MetricStatus.OPTIMAL else MetricStatus.NEUTRAL
        } else MetricStatus.NEUTRAL
        
        val durationVisual = DashboardMetricScalePreparer.goal(
            value = sleepMins?.toFloat(),
            target = goalMins.toFloat(),
            bands = emptyList()
        )
        
        val sleepDurationTitle =
            resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_sleep_duration)
        val sleepDurationValueText = m?.sleepDurationDisplay ?: "—"
        val sleepDurationDescription = when {
            durationVisual.unavailableReason != null ->
                unavailableDescription(sleepDurationTitle, durationVisual.unavailableReason)
            durationVisual.isAboveTarget -> resourceProvider.getString(
                app.readylytics.health.feature.dashboard.R.string.semantics_value_note_format,
                sleepDurationTitle,
                sleepDurationValueText,
                resourceProvider.getString(
                    app.readylytics.health.feature.dashboard.R.string.goal_above_target_description,
                ),
            )
            else -> resourceProvider.getString(
                app.readylytics.health.feature.dashboard.R.string.semantics_goal_format,
                sleepDurationTitle,
                sleepDurationValueText,
                DailyMetricsMapper.formatSleepDuration(goalMins) ?: "—",
            )
        }
        map[CardId.SLEEP_DURATION] = DashboardMetricPresentation(
            title = sleepDurationTitle,
            valueText = sleepDurationValueText,
            unitText = "",
            secondaryText = null,
            status = durationStatus,
            tooltip = "",
            accessibilityDescription = sleepDurationDescription,
            visual = durationVisual
        )
        
        // 6. HRV
        val hrvBaseline = m?.hrvBaselineMeanRaw
        val hrvStatus = if (summary?.nocturnalHrv != null) MetricStatus.OPTIMAL else MetricStatus.NEUTRAL
        val hrvPoorRatio = preferences.hrvWarningThreshold - (1f - preferences.hrvWarningThreshold)
        val hrvVisual = DashboardMetricScalePreparer.personalBaseline(
            value = summary?.nocturnalHrv?.toFloat(),
            baseline = m?.hrvBaselineMeanRaw,
            axisMinimumRatio = hrvPoorRatio,
            axisMaximumRatio = 1f + (1f - hrvPoorRatio),
            bands = if (hrvBaseline != null) listOf(
                RawMetricBand(hrvBaseline * hrvPoorRatio, hrvBaseline * preferences.hrvWarningThreshold, MetricStatus.WARNING),
                RawMetricBand(hrvBaseline * preferences.hrvWarningThreshold, hrvBaseline * preferences.hrvOptimalThreshold, MetricStatus.NEUTRAL),
                RawMetricBand(hrvBaseline * preferences.hrvOptimalThreshold, hrvBaseline * (1f + (1f - hrvPoorRatio)), MetricStatus.OPTIMAL)
            ) else emptyList(),
            baselineReady = summary?.isCalibrating == false && hrvBaseline != null && hrvBaseline > 0f
        )
        val hrvTitle = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_hrv)
        val hrvValueText = summary?.nocturnalHrv?.toString() ?: "—"
        val hrvUnitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_ms)
        val hrvDescription = hrvVisual.unavailableReason?.let { reason ->
            unavailableDescription(hrvTitle, reason)
        } ?: run {
            val ratio = hrvVisual.ratio
            val relationRes = when {
                ratio != null && ratio >= preferences.hrvOptimalThreshold ->
                    app.readylytics.health.feature.dashboard.R.string.personal_baseline_above_range_description
                ratio != null && ratio <= preferences.hrvWarningThreshold ->
                    app.readylytics.health.feature.dashboard.R.string.personal_baseline_below_range_description
                else -> app.readylytics.health.feature.dashboard.R.string.personal_baseline_within_range_description
            }
            resourceProvider.getString(
                app.readylytics.health.feature.dashboard.R.string.semantics_value_note_format,
                hrvTitle,
                "$hrvValueText $hrvUnitText",
                resourceProvider.getString(relationRes),
            )
        }
        map[CardId.HRV] = DashboardMetricPresentation(
            title = hrvTitle,
            valueText = hrvValueText,
            unitText = hrvUnitText,
            secondaryText = null,
            status = hrvStatus,
            tooltip = "",
            accessibilityDescription = hrvDescription,
            visual = hrvVisual
        )
        
        // 7. SLEEP RHR
        val rhrBaseline = m?.rhrBaselineRaw
        val sleepRhrStatus = if (summary?.restingHeartRate != null) MetricStatus.OPTIMAL else MetricStatus.NEUTRAL
        val rhrPoorRatio = preferences.rhrWarningThreshold + (preferences.rhrWarningThreshold - 1f)
        val sleepRhrVisual = DashboardMetricScalePreparer.personalBaseline(
            value = summary?.restingHeartRate?.toFloat(),
            baseline = m?.rhrBaselineRaw,
            axisMinimumRatio = 1f - (rhrPoorRatio - 1f),
            axisMaximumRatio = rhrPoorRatio,
            bands = if (rhrBaseline != null) listOf(
                RawMetricBand(rhrBaseline * (1f - (rhrPoorRatio - 1f)), rhrBaseline * preferences.rhrOptimalThreshold, MetricStatus.OPTIMAL),
                RawMetricBand(rhrBaseline * preferences.rhrOptimalThreshold, rhrBaseline * preferences.rhrWarningThreshold, MetricStatus.NEUTRAL),
                RawMetricBand(rhrBaseline * preferences.rhrWarningThreshold, rhrBaseline * rhrPoorRatio, MetricStatus.WARNING)
            ) else emptyList(),
            baselineReady = summary?.isCalibrating == false && rhrBaseline != null && rhrBaseline > 0f
        )
        map[CardId.SLEEP_RHR] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_sleep_rhr),
            valueText = m?.restingHeartRateRounded?.toString() ?: "—",
            unitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_bpm),
            secondaryText = null,
            status = sleepRhrStatus,
            tooltip = "",
            accessibilityDescription = "",
            visual = sleepRhrVisual
        )

        // 8. RESTING HR
        val rhrStatus = if (summary?.restingHeartRate != null) MetricStatus.OPTIMAL else MetricStatus.NEUTRAL
        val rhrVisual = DashboardMetricScalePreparer.personalBaseline(
            value = summary?.restingHeartRate?.toFloat(),
            baseline = m?.rhrBaselineRaw,
            axisMinimumRatio = 1f - (rhrPoorRatio - 1f),
            axisMaximumRatio = rhrPoorRatio,
            bands = if (rhrBaseline != null) listOf(
                RawMetricBand(rhrBaseline * (1f - (rhrPoorRatio - 1f)), rhrBaseline * preferences.rhrOptimalThreshold, MetricStatus.OPTIMAL),
                RawMetricBand(rhrBaseline * preferences.rhrOptimalThreshold, rhrBaseline * preferences.rhrWarningThreshold, MetricStatus.NEUTRAL),
                RawMetricBand(rhrBaseline * preferences.rhrWarningThreshold, rhrBaseline * rhrPoorRatio, MetricStatus.WARNING)
            ) else emptyList(),
            baselineReady = summary?.isCalibrating == false && rhrBaseline != null && rhrBaseline > 0f
        )
        map[CardId.RESTING_HR] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_resting_hr),
            valueText = m?.restingHeartRateRounded?.toString() ?: "—",
            unitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_bpm),
            secondaryText = null,
            status = rhrStatus,
            tooltip = "",
            accessibilityDescription = "",
            visual = rhrVisual
        )
        
        // 9. RAS DAILY
        val rasVal = m?.rasRounded?.toFloat()
        val rasStatus = if (rasVal != null) {
            if (rasVal >= 10f) MetricStatus.OPTIMAL else MetricStatus.WARNING
        } else MetricStatus.NEUTRAL
        val rasVisual = DashboardMetricScalePreparer.score(rasVal, 0f, 100f, listOf(RawMetricBand(0f, 50f, MetricStatus.POOR), RawMetricBand(50f, 75f, MetricStatus.WARNING), RawMetricBand(75f, 100f, MetricStatus.OPTIMAL)))
        map[CardId.RAS_DAILY] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_ras),
            valueText = m?.rasRounded?.toString() ?: "—",
            unitText = "",
            secondaryText = null,
            status = rasStatus,
            tooltip = "",
            accessibilityDescription = "",
            visual = rasVisual
        )
        
        // 10. SLEEP EFFICIENCY
        val efficiency = lastSleepSession?.efficiency
        val effStatus = if (efficiency != null) {
            when {
                efficiency >= 0.85f -> MetricStatus.OPTIMAL
                efficiency >= 0.75f -> MetricStatus.NEUTRAL
                efficiency >= 0.65f -> MetricStatus.WARNING
                else -> MetricStatus.POOR
            }
        } else MetricStatus.NEUTRAL
        
        val effValText = if (efficiency == null) "—" else if (efficiency == 0f) "0" else String.format(Locale.getDefault(), "%.0f", efficiency * 100)
        val effVisual = DashboardMetricScalePreparer.score(efficiency?.let { it * 100f }, 0f, 100f, listOf(RawMetricBand(0f, 70f, MetricStatus.POOR), RawMetricBand(70f, 80f, MetricStatus.WARNING), RawMetricBand(80f, 85f, MetricStatus.NEUTRAL), RawMetricBand(85f, 100f, MetricStatus.OPTIMAL)))
        map[CardId.SLEEP_EFFICIENCY] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.core.ui.R.string.card_title_sleep_efficiency),
            valueText = effValText,
            unitText = "%",
            secondaryText = null,
            status = effStatus,
            tooltip = "",
            accessibilityDescription = "",
            visual = effVisual
        )
        
        val spo2 = summary?.avgSleepingSpo2
        val roundedSpo2 = spo2?.roundToInt()
        val spo2Status = when {
            roundedSpo2 == null -> MetricStatus.CALIBRATING
            roundedSpo2 >= 95 -> MetricStatus.OPTIMAL
            roundedSpo2 >= 90 -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }
        val spo2Visual = DashboardMetricScalePreparer.score(
            spo2, 80f, 100f, listOf(
                RawMetricBand(80f, 90f, MetricStatus.POOR),
                RawMetricBand(90f, 95f, MetricStatus.WARNING),
                RawMetricBand(95f, 98f, MetricStatus.NEUTRAL),
                RawMetricBand(98f, 100f, MetricStatus.OPTIMAL)
            )
        )
        map[CardId.OXYGEN_SATURATION] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_oxygen_saturation),
            valueText = roundedSpo2?.toString() ?: "—",
            unitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_percent),
            secondaryText = null,
            status = spo2Visual.getResolvedStatus(),
            tooltip = "",
            accessibilityDescription = "",
            visual = spo2Visual
        )
        
        // 12. BLOOD PRESSURE
        val systolic = summary?.bloodPressureSystolic ?: 0
        val diastolic = summary?.bloodPressureDiastolic ?: 0
        val bpStatus = if (systolic > 0 && diastolic > 0) {
            HealthMetricsCalculator.assessBloodPressure(systolic, diastolic).toMetricStatus()
        } else {
            MetricStatus.NEUTRAL
        }
        map[CardId.BLOOD_PRESSURE] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_blood_pressure),
            valueText = m?.bloodPressureDisplay ?: "$systolic/$diastolic",
            unitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_mmHg),
            secondaryText = null,
            status = bpStatus,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricVisual.ValueOnly
        )

        
        // 13. HEART RATE
        map[CardId.HEART_RATE] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_heart_rate),
            valueText = heartRateSummary?.avgBpm?.toString() ?: "—",
            unitText = "bpm",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricVisual.ValueOnly
        )
        
        // 14. CIRCADIAN
        val circReady = circadianResult as? app.readylytics.health.domain.scoring.CircadianConsistencyResult.Ready
        map[CardId.CIRCADIAN_CONSISTENCY] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_circadian_consistency),
            valueText = circReady?.score?.toString() ?: "—",
            unitText = "",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricScalePreparer.score(circReady?.score?.toFloat(), 0f, 100f, listOf(RawMetricBand(0f, 40f, MetricStatus.POOR), RawMetricBand(40f, 60f, MetricStatus.WARNING), RawMetricBand(60f, 80f, MetricStatus.NEUTRAL), RawMetricBand(80f, 100f, MetricStatus.OPTIMAL)))
        )
        
        // 15. STRAIN RATIO
        map[CardId.STRAIN_RATIO] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.core.ui.R.string.card_title_strain_ratio),
            valueText = m?.strainRatioDisplay ?: "—",
            unitText = "",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricScalePreparer.score(m?.strainRatioRaw, 0f, 2f, listOf(RawMetricBand(0f, 0.5f, MetricStatus.POOR), RawMetricBand(0.5f, 0.8f, MetricStatus.WARNING), RawMetricBand(0.8f, 1.3f, MetricStatus.OPTIMAL), RawMetricBand(1.3f, 1.5f, MetricStatus.WARNING), RawMetricBand(1.5f, 2.0f, MetricStatus.POOR)))
        )

        return map
    }
}
