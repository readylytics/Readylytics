package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.data.preferences.UserPreferences
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
import java.time.LocalDate
import javax.inject.Inject

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
        
        map[CardId.SLEEP_SCORE] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_sleep_score),
            valueText = m?.sleepScoreRounded?.toString() ?: "—",
            unitText = "",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricScalePreparer.score(summary?.sleepScore, 0f, 100f, scoreBands)
        )
        
        val readinessScore = m?.readinessRounded?.toFloat()
        
        map[CardId.READINESS] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.core.ui.R.string.card_title_readiness),
            valueText = m?.readinessRounded?.toString() ?: "—",
            unitText = "",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricScalePreparer.score(readinessScore, 0f, 100f, scoreBands)
        )
        
        val heightM = (preferences.heightCm ?: 0f) / 100f
        val isHeightValid = heightM > 0f
        val bmi = if (isHeightValid) summary?.weightKg?.let { it / (heightM * heightM) } else null
        
        map[CardId.WEIGHT] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_weight),
            valueText = summary?.weightKg?.toString() ?: "—",
            unitText = "kg",
            secondaryText = bmi?.let { resourceProvider.getString(app.readylytics.health.core.ui.R.string.bmi_secondary_text, String.format("%.1f", it)) },
            status = MetricStatus.NEUTRAL,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricScalePreparer.referenceRange(
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
        )
        
        val bodyFatPercent = summary?.bodyFatPercent
        val bodyFatMidpoint = BodyCompositionAssessment.assessBodyFat(bodyFatPercent ?: 20f, preferences.physiologyProfile, preferences.gender).reference.referenceMidpoint
        
        map[CardId.BODY_FAT] = DashboardMetricPresentation(
            title = resourceProvider.getString(app.readylytics.health.feature.dashboard.R.string.card_title_body_fat),
            valueText = bodyFatPercent?.toString() ?: "—",
            unitText = "%",
            secondaryText = null,
            status = MetricStatus.NEUTRAL,
            tooltip = "",
            accessibilityDescription = "",
            visual = DashboardMetricScalePreparer.referenceRange(
                value = bodyFatPercent,
                minimum = 0f,
                midpoint = bodyFatMidpoint,
                maximum = 40f,
                bands = emptyList(),
                scaleAvailable = true,
                unavailableReason = null
            )
        )
        
        return map
    }
}
