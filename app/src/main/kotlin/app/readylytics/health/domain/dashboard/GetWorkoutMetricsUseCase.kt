package app.readylytics.health.domain.dashboard

import app.readylytics.health.R
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.strainRatioStatus
import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.ui.dashboard.CardData
import app.readylytics.health.ui.dashboard.DashboardAction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetWorkoutMetricsUseCase
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
    ) {
        data class WorkoutMetrics(
            val strainRatioCard: CardData?,
        )

        operator fun invoke(
            summary: DailySummary?,
            metrics: DailyMetrics? = null,
        ): Result<WorkoutMetrics> =
            try {
                if (summary == null) {
                    return@invoke Result.success(WorkoutMetrics(null))
                }

                val strainRatioCard =
                    (if (metrics != null) metrics.strainRatioRaw else summary.strainRatio)?.let { strainRatio ->
                        createStrainRatioCard(
                            strainRatio = strainRatio,
                            displayValue = metrics?.strainRatioDisplay ?: MetricFormatter.formatStrain(strainRatio),
                        )
                    }
                        ?: CardData(
                            title = resourceProvider.getString(R.string.card_title_strain_ratio),
                            value = "—",
                            unit = "",
                            status = MetricStatus.CALIBRATING,
                            action = DashboardAction.NAVIGATE_WORKOUTS,
                            tooltip = resourceProvider.getString(R.string.tooltip_strain_ratio),
                        )

                Result.success(WorkoutMetrics(strainRatioCard))
            } catch (e: Exception) {
                Result.failure("Failed to compute workout metrics", "WORKOUT_METRICS_ERROR")
            }

        private fun createStrainRatioCard(
            strainRatio: Float,
            displayValue: String,
        ): CardData {
            val status = strainRatio.strainRatioStatus()

            return CardData(
                title = resourceProvider.getString(R.string.card_title_strain_ratio),
                value = displayValue,
                unit = "",
                status = status,
                action = DashboardAction.NAVIGATE_WORKOUTS,
                tooltip = resourceProvider.getString(R.string.tooltip_strain_ratio),
            )
        }
    }
