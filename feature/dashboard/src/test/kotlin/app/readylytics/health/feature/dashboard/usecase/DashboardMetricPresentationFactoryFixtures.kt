package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier
import app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculator

/** Builds the real facade with real sub-factories, mirroring the Hilt graph for tests. */
internal fun dashboardMetricPresentationFactory(
    resourceProvider: ResourceProvider,
): DashboardMetricPresentationFactory =
    DashboardMetricPresentationFactory(
        resourceProvider = resourceProvider,
        residualFatiguePresentationFactory = ResidualFatiguePresentationFactory(resourceProvider),
        cardioFactory =
            DashboardCardioMetricPresentationFactory(
                resourceProvider,
                TrainingStressBalanceCalculator(),
                CooperNormsClassifier(),
            ),
        scoreFactory = DashboardScoreMetricPresentationFactory(resourceProvider),
        bodyCompositionFactory = DashboardBodyCompositionMetricPresentationFactory(resourceProvider),
        vitalsFactory = DashboardVitalsMetricPresentationFactory(resourceProvider),
        sleepRhythmFactory = DashboardSleepRhythmMetricPresentationFactory(resourceProvider),
        recoveryFactory = DashboardRecoveryMetricPresentationFactory(resourceProvider),
    )
