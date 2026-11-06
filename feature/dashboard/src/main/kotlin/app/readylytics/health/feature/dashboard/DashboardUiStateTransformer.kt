package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailyMetricsMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.scoring.domain.dashboard.DerivedInsights
import app.readylytics.health.core.scoring.domain.dashboard.InsightDeriver
import app.readylytics.health.core.scoring.domain.insights.InsightContext
import app.readylytics.health.core.scoring.domain.insights.InsightEngine
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.feature.dashboard.usecase.LiveResidualFatigue
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class DashboardUiStateTransformer
    @Inject
    constructor(
        private val useCases: DashboardUseCases,
        private val clock: Clock,
    ) {
        private var lastFatigueCacheKey: FatigueCacheKey? = null
        private var lastFatigueValue: LiveResidualFatigue = LiveResidualFatigue.NotApplicable

        suspend fun transformToUiState(
            basicInputs: DashboardBasicInputs,
            cardState: DashboardCardState,
            minuteBucket: Long,
            hrSummary: HeartRateDaySummary? = null,
            todayStrainIncrease: Float? = null,
            todayRasIncrease: Float? = null,
        ): DashboardUiState {
            val selectedDate = basicInputs.selectedDate
            val liveResidualFatigue = resolveCurrentResidualFatigue(selectedDate, basicInputs, minuteBucket)
            val sessionSummary = resolveDashboardSleepSessionSummary(cardState.lastSleepSession)

            val cardsResult =
                useCases.getDashboardData.invoke(
                    summary = basicInputs.summary,
                    prefs = basicInputs.userPreferences,
                    date = selectedDate,
                    lastSleepSession = sessionSummary,
                    rasSummaries = basicInputs.rasSummaries,
                    circadianResult = basicInputs.circadianResult,
                    heartRateSummary = hrSummary,
                    todayStrainIncrease = todayStrainIncrease,
                    todayRasIncrease = todayRasIncrease,
                    bodyTempBaseline = basicInputs.bodyTempBaseline,
                    liveResidualFatigue = liveResidualFatigue,
                )

            val cards = cardsResult.getOrNull()
            val derived = deriveInsights(basicInputs, selectedDate, clock)
            val yesterdayMetrics = resolveYesterdayMetrics(basicInputs, selectedDate)

            return DashboardUiState(
                summary = basicInputs.summary,
                selectedDate = selectedDate,
                today = LocalDate.now(clock),
                cardDataMap = cards?.cardDataMap ?: emptyMap(),
                circadianConsistency = basicInputs.circadianResult,
                restingHrCard = cards?.cardDataMap?.get(CardId.RESTING_HR),
                rasDailyBreakdown = cards?.rasDailyBreakdown ?: emptyList(),
                stepCount = basicInputs.summary?.stepCount,
                stepGoal = basicInputs.userPreferences.stepGoal,
                lastSleepSession = sessionSummary,
                cardConfigurations = cardState.pendingConfiguration ?: cardState.cardConfiguration,
                isManagingCards = cardState.isManagingCards,
                isCalibrating = basicInputs.summary?.isCalibrating ?: false,
                errorMessage = if (cardsResult.isFailure) "Failed to load dashboard data" else null,
                heartRateDaySummary = hrSummary,
                activeInsightTypes = derived.active,
                currentInsight = derived.current,
                currentInsightParams = derived.currentParams,
                visibleInsightQueue = derived.visibleQueue,
                dismissedInsightCount = derived.dismissedCount,
                goalSleepHours = basicInputs.userPreferences.goalSleepHours,
                userPreferences = basicInputs.userPreferences,
                yesterdaySleepScoreRounded = yesterdayMetrics?.sleepScoreRounded,
                yesterdayReadiness = yesterdayMetrics?.readinessRounded?.toFloat(),
            )
        }

        private suspend fun resolveCurrentResidualFatigue(
            selectedDate: LocalDate,
            basicInputs: DashboardBasicInputs,
            minuteBucket: Long,
        ): LiveResidualFatigue {
            val cacheKey =
                FatigueCacheKey(selectedDate, basicInputs.userPreferences, basicInputs.summary, minuteBucket)
            if (cacheKey == lastFatigueCacheKey) return lastFatigueValue
            val value =
                try {
                    useCases.getCurrentResidualFatigue(selectedDate, basicInputs.userPreferences.scoringZone())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logE(TAG, e) { "Failed to resolve live residual fatigue" }
                    LiveResidualFatigue.Unavailable
                }
            lastFatigueCacheKey = cacheKey
            lastFatigueValue = value
            return value
        }

        internal fun resolveDashboardSleepSessionSummary(session: SleepSessionData?): SleepSessionSummary? {
            session ?: return null
            return SleepSessionSummary(
                efficiency = session.efficiency,
                startTime = session.startTime,
                endTime = session.endTime,
            )
        }

        internal fun deriveInsights(
            basicInputs: DashboardBasicInputs,
            selectedDate: LocalDate,
            clock: Clock,
        ): DerivedInsights {
            val engineFindings =
                basicInputs.summary?.let { summary ->
                    InsightEngine.evaluate(
                        InsightContext(
                            today = summary,
                            circadianResult = basicInputs.circadianResult ?: CircadianConsistencyResult.MissingData,
                            goalSleepMinutes = (basicInputs.userPreferences.goalSleepHours * 60).toInt(),
                            stepGoal = basicInputs.userPreferences.stepGoal,
                            recentDays = basicInputs.rasSummaries,
                            nowMinutesOfDay = nowMinutesOfDayFor(selectedDate, clock),
                            prefs = basicInputs.userPreferences,
                        ),
                    )
                } ?: emptyList()
            return InsightDeriver.derive(
                recoveryFlags = basicInputs.summary?.recoveryFlags,
                engineFindings = engineFindings,
                dismissedTypes = basicInputs.dismissedInsightTypes,
            )
        }

        internal fun resolveYesterdayMetrics(
            basicInputs: DashboardBasicInputs,
            selectedDate: LocalDate,
        ) = basicInputs.rasSummaries
            .firstOrNull { it.date == selectedDate.minusDays(1) }
            ?.let { DailyMetricsMapper.toMetrics(it, basicInputs.userPreferences) }

        private data class FatigueCacheKey(
            val selectedDate: LocalDate,
            val userPreferences: UserPreferences,
            val summary: DailySummary?,
            val minuteBucket: Long,
        )

        companion object {
            private const val TAG = "DashboardUiStateTransformer"
        }
    }
