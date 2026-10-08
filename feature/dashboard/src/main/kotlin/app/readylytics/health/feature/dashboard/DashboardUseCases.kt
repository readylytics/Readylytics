package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.scoring.domain.airecommendation.GetDailyPromptDataUseCase
import app.readylytics.health.feature.dashboard.usecase.GetCurrentResidualFatigueUseCase
import app.readylytics.health.feature.dashboard.usecase.GetDashboardDataUseCase
import app.readylytics.health.feature.dashboard.usecase.ObserveDashboardRasIncreaseUseCase
import app.readylytics.health.feature.dashboard.usecase.ObserveDashboardStrainIncreaseUseCase
import javax.inject.Inject

class DashboardUseCases
    @Inject
    constructor(
        val getDashboardData: GetDashboardDataUseCase,
        val observeDashboardStrainIncrease: ObserveDashboardStrainIncreaseUseCase,
        val observeDashboardRasIncrease: ObserveDashboardRasIncreaseUseCase,
        val getDailyPromptData: GetDailyPromptDataUseCase,
        val getCurrentResidualFatigue: GetCurrentResidualFatigueUseCase,
    )
