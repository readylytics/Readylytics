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
        return emptyMap()
    }
}
