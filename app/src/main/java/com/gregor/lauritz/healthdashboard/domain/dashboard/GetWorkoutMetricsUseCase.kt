package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.model.strainRatioStatus
import com.gregor.lauritz.healthdashboard.domain.util.ResourceProvider
import com.gregor.lauritz.healthdashboard.ui.dashboard.CardData
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardAction
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

        operator fun invoke(summary: DailySummary?): Result<WorkoutMetrics> =
            try {
                if (summary == null) {
                    return@invoke Result.success(WorkoutMetrics(null))
                }

                val strainRatioCard =
                    summary.strainRatio?.let { createStrainRatioCard(it) }
                        ?: CardData(
                            title = "Strain Ratio",
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

        private fun createStrainRatioCard(strainRatio: Float): CardData {
            val status = strainRatio.strainRatioStatus()
            val value = String.format("%.2f", strainRatio)

            return CardData(
                title = "Strain Ratio",
                value = value,
                unit = "",
                status = status,
                action = DashboardAction.NAVIGATE_WORKOUTS,
                tooltip = resourceProvider.getString(R.string.tooltip_strain_ratio),
            )
        }
    }
