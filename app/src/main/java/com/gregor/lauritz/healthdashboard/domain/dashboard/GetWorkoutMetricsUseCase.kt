package com.gregor.lauritz.healthdashboard.domain.dashboard

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.dashboard.CardData
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardAction
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetWorkoutMetricsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class WorkoutMetrics(
        val loadScoreCard: CardData?,
        val strainRatioCard: CardData?,
    )

    operator fun invoke(
        summary: DailySummary?,
    ): WorkoutMetrics {
        if (summary == null) {
            return WorkoutMetrics(null, null)
        }

        val loadScoreCard = summary.loadScore?.let { loadScore ->
            createLoadScoreCard(loadScore)
        }

        val strainRatioCard = summary.strainRatio?.let { strainRatio ->
            createStrainRatioCard(strainRatio)
        }

        return WorkoutMetrics(loadScoreCard, strainRatioCard)
    }

    private fun createLoadScoreCard(loadScore: Float): CardData {
        val status = loadScore.loadScoreStatus()
        val value = loadScore.toInt().toString()

        return CardData(
            title = "Load Score",
            value = value,
            unit = "",
            status = status,
            action = DashboardAction.NAVIGATE_WORKOUTS,
            tooltip = context.getString(R.string.tooltip_load_score),
        )
    }

    private fun createStrainRatioCard(strainRatio: Float): CardData {
        val status = strainRatio.strainRatioStatus()
        val value = String.format("%.1fx", strainRatio)

        return CardData(
            title = "Strain Ratio",
            value = value,
            unit = "",
            status = status,
            action = DashboardAction.NAVIGATE_WORKOUTS,
            tooltip = context.getString(R.string.tooltip_strain_ratio),
        )
    }

    private fun Float.loadScoreStatus(): MetricStatus {
        return when {
            this >= 85f -> MetricStatus.OPTIMAL
            this >= 60f -> MetricStatus.NEUTRAL
            this >= 40f -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }
    }

    private fun Float.strainRatioStatus(): MetricStatus {
        return when {
            this in 0.8f..1.3f -> MetricStatus.OPTIMAL
            this < 0.8f || this > 1.5f -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }
    }
}
