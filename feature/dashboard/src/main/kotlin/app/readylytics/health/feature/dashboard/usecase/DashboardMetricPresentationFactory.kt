package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailyMetricsMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.model.domain.model.assessHrv
import app.readylytics.health.core.model.domain.model.assessRhr
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier
import app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculator
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import javax.inject.Inject
import javax.inject.Singleton
import app.readylytics.health.core.ui.R as CoreUiR

@Singleton
class DashboardMetricPresentationFactory
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
        private val residualFatiguePresentationFactory: ResidualFatiguePresentationFactory,
        private val cardioFactory: DashboardCardioMetricPresentationFactory,
        private val scoreFactory: DashboardScoreMetricPresentationFactory,
        private val bodyCompositionFactory: DashboardBodyCompositionMetricPresentationFactory,
        private val vitalsFactory: DashboardVitalsMetricPresentationFactory,
        private val sleepRhythmFactory: DashboardSleepRhythmMetricPresentationFactory,
        private val recoveryFactory: DashboardRecoveryMetricPresentationFactory,
    ) {
        // Secondary constructor for test backwards compatibility
        constructor(
            resourceProvider: ResourceProvider,
            residualFatiguePresentationFactory: ResidualFatiguePresentationFactory =
                ResidualFatiguePresentationFactory(resourceProvider),
            cardioFactory: DashboardCardioMetricPresentationFactory =
                DashboardCardioMetricPresentationFactory(
                    resourceProvider,
                    TrainingStressBalanceCalculator(),
                    CooperNormsClassifier(),
                ),
        ) : this(
            resourceProvider = resourceProvider,
            residualFatiguePresentationFactory = residualFatiguePresentationFactory,
            cardioFactory = cardioFactory,
            scoreFactory = DashboardScoreMetricPresentationFactory(resourceProvider),
            bodyCompositionFactory = DashboardBodyCompositionMetricPresentationFactory(resourceProvider),
            vitalsFactory = DashboardVitalsMetricPresentationFactory(resourceProvider),
            sleepRhythmFactory = DashboardSleepRhythmMetricPresentationFactory(resourceProvider),
            recoveryFactory = DashboardRecoveryMetricPresentationFactory(resourceProvider),
        )

        fun build(
            summary: DailySummary?,
            preferences: UserPreferences,
            lastSleepSession: SleepSessionSummary?,
            circadianResult: CircadianConsistencyResult?,
            heartRateSummary: HeartRateDaySummary?,
            todayStrainIncrease: Float? = null,
            todayRasIncrease: Float? = null,
            bodyTempBaseline: Float? = null,
            liveResidualFatigue: LiveResidualFatigue = LiveResidualFatigue.NotApplicable,
        ): Map<CardId, UniversalMetricPresentation> {
            val map = mutableMapOf<CardId, UniversalMetricPresentation>()
            val unavailableValueText = resourceProvider.getString(CoreUiR.string.metric_value_unavailable)
            val scoreMaximumText = resourceProvider.getString(CoreUiR.string.score_maximum)
            val m = if (summary != null) DailyMetricsMapper.toMetrics(summary, preferences) else null

            val hrvAssessment =
                assessHrv(
                    value = summary?.nocturnalHrv,
                    baseline = m?.hrvBaselineRounded,
                    optimalRatio = preferences.hrvOptimalThreshold,
                    warningRatio = preferences.hrvWarningThreshold,
                )
            val rhrAssessment =
                assessRhr(
                    value = summary?.restingHeartRate,
                    baseline = m?.rhrBaselineRounded,
                    optimalRatio = preferences.rhrOptimalThreshold,
                    warningRatio = preferences.rhrWarningThreshold,
                )

            map.putAll(
                recoveryFactory.build(
                    summary = summary,
                    metrics = m,
                    preferences = preferences,
                    lastSleepSession = lastSleepSession,
                    hrvAssessment = hrvAssessment,
                    rhrAssessment = rhrAssessment,
                    todayRasIncrease = todayRasIncrease,
                ),
            )

            map.putAll(
                scoreFactory.build(
                    summary = summary,
                    preferences = preferences,
                    unavailableValueText = unavailableValueText,
                    scoreMaximumText = scoreMaximumText,
                    todayStrainIncrease = todayStrainIncrease,
                ),
            )

            map.putAll(
                bodyCompositionFactory.build(
                    summary = summary,
                    preferences = preferences,
                    unavailableValueText = unavailableValueText,
                ),
            )

            map.putAll(
                vitalsFactory.build(
                    summary = summary,
                    metrics = m,
                    preferences = preferences,
                    heartRateSummary = heartRateSummary,
                    bodyTempBaseline = bodyTempBaseline,
                    unavailableValueText = unavailableValueText,
                ),
            )

            map.putAll(
                sleepRhythmFactory.build(
                    lastSleepSession = lastSleepSession,
                    circadianResult = circadianResult,
                    unavailableValueText = unavailableValueText,
                    scoreMaximumText = scoreMaximumText,
                ),
            )

            map[CardId.RESIDUAL_FATIGUE] =
                residualFatiguePresentationFactory.build(
                    summary = summary,
                    preferences = preferences,
                    unavailableValueText = unavailableValueText,
                    liveResidualFatigue = liveResidualFatigue,
                )

            map.putAll(
                cardioFactory.build(
                    summary = summary,
                    preferences = preferences,
                    unavailableValueText = unavailableValueText,
                ),
            )

            return map
        }
    }
