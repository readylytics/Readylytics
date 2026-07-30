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
        map[CardId.SLEEP_SCORE] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_sleep_score),
            valueText = m?.sleepScoreRounded?.toString() ?: "—",
            unitText = "",
            secondaryText = null,
            status = sleepScoreVisual.getResolvedStatus(),
            tooltip = "",
            accessibilityDescription = "",
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
        map[CardId.WEIGHT] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_weight),
            valueText = m?.weightKgDisplay?.replace(" kg", "")?.replace(" lbs", "") ?: "—",
            unitText = if (preferences.unitSystem == app.readylytics.health.domain.preferences.UnitSystem.METRIC) "kg" else "lbs",
            secondaryText = bmi?.let { resourceProvider.getString(app.readylytics.health.core.ui.R.string.bmi_secondary_text, String.format("%.1f", it)) },
            status = weightVisual.getResolvedStatus(),
            tooltip = "",
            accessibilityDescription = "",
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
        val bodyFatVisual = DashboardMetricScalePreparer.referenceRange(
            value = bodyFatPercent,
            minimum = 0f,
            midpoint = bodyFatMidpoint,
            maximum = 40f,
            bands = emptyList(),
            scaleAvailable = true,
            unavailableReason = null
        )
        map[CardId.BODY_FAT] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_body_fat),
            valueText = bodyFatPercent?.toString() ?: "—",
            unitText = "%",
            secondaryText = null,
            status = bodyFatStatusVal,
            tooltip = "",
            accessibilityDescription = "",
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
        
        map[CardId.SLEEP_DURATION] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_sleep_duration),
            valueText = m?.sleepDurationDisplay ?: "—",
            unitText = "",
            secondaryText = null,
            status = durationStatus,
            tooltip = "",
            accessibilityDescription = "",
            visual = durationVisual
        )
        
        // 6. HRV
        val hrvStatus = if (summary?.nocturnalHrv != null) MetricStatus.OPTIMAL else MetricStatus.NEUTRAL
        val hrvVisual = DashboardMetricScalePreparer.personalBaseline(
            value = summary?.nocturnalHrv?.toFloat(),
            baseline = m?.hrvBaselineMeanRaw,
            axisMinimumRatio = 0.5f,
            axisMaximumRatio = 1.5f,
            bands = emptyList(),
            baselineReady = true
        )
        map[CardId.HRV] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_hrv),
            valueText = summary?.nocturnalHrv?.toString() ?: "—",
            unitText = resourceProvider.getString(app.readylytics.health.core.ui.R.string.unit_ms),
            secondaryText = null,
            status = hrvStatus,
            tooltip = "",
            accessibilityDescription = "",
            visual = hrvVisual
        )
        
        // 7. SLEEP RHR
        val sleepRhrStatus = if (summary?.restingHeartRate != null) MetricStatus.OPTIMAL else MetricStatus.NEUTRAL
        val sleepRhrVisual = DashboardMetricScalePreparer.personalBaseline(
            value = summary?.restingHeartRate?.toFloat(),
            baseline = m?.rhrBaselineRaw,
            axisMinimumRatio = 0.5f,
            axisMaximumRatio = 1.5f,
            bands = emptyList(),
            baselineReady = true
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
            axisMinimumRatio = 0.5f,
            axisMaximumRatio = 1.5f,
            bands = emptyList(),
            baselineReady = true
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
        val rasVisual = DashboardMetricScalePreparer.score(rasVal, 0f, 100f, emptyList())
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
        val effVisual = DashboardMetricScalePreparer.score((efficiency ?: 0f) * 100f, 0f, 100f, emptyList())
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
            visual = DashboardMetricScalePreparer.score(circReady?.score?.toFloat(), 0f, 100f, emptyList())
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
            visual = DashboardMetricScalePreparer.score(m?.strainRatioRaw, 0f, 2f, emptyList())
        )

        return map
    }
}
