package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.model.DailyMetricsMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionChecker
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.scoring.domain.airecommendation.DailyPromptFormatter
import app.readylytics.health.core.scoring.domain.dashboard.DerivedInsights
import app.readylytics.health.core.scoring.domain.dashboard.InsightDeriver
import app.readylytics.health.core.scoring.domain.insights.InsightContext
import app.readylytics.health.core.scoring.domain.insights.InsightEngine
import app.readylytics.health.core.scoring.domain.insights.InsightParams
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.common.BaseViewModel
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.feature.dashboard.usecase.LiveResidualFatigue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import app.readylytics.health.core.ui.R as CoreUiR

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val repositories: DashboardRepositories,
        private val useCases: DashboardUseCases,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val fatigueTicker: DashboardFatigueTicker,
        private val permissionChecker: HealthConnectPermissionChecker,
        private val clock: Clock,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : BaseViewModel(),
        DashboardCardActions {
        fun validateSelectedDate(date: LocalDate): Result<LocalDate> =
            if (date <= LocalDate.now(clock)) {
                Result.success(date)
            } else {
                Result.failure("Cannot select future dates", "INVALID_DATE")
            }

        private val cardManagementDelegate =
            CardManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                persist = repositories.cardConfig::updateDashboardCardConfigurations,
                scope = viewModelScope,
                hasBodyTemperaturePermission = { permissionChecker.hasBodyTemperaturePermission() },
                hasStepsPermission = { permissionChecker.hasStepsPermission() },
                hasWeightPermission = { permissionChecker.hasWeightPermission() },
                hasBodyFatPermission = { permissionChecker.hasBodyFatPermission() },
                hasBloodPressurePermission = { permissionChecker.hasBloodPressurePermission() },
                hasOxygenSaturationPermission = { permissionChecker.hasOxygenSaturationPermission() },
            )

        override val cardActionHandler: DashboardCardActionHandler =
            DashboardCardActionHandler(cardManagementDelegate) { uiState.value.cardConfigurations }

        val isManagingCards: StateFlow<Boolean> = cardManagementDelegate.isManagingCards

        val uiState: StateFlow<DashboardUiState> =
            // The expensive transform (InsightEngine + GetDashboardDataUseCase) is driven only
            // by the data flows (basic/card/hr). Realtime sync state is merged in afterwards via
            // a cheap copy, so recalcProgress/isSyncing ticks during a resync no longer re-run
            // insight evaluation and card building. distinctUntilChanged guards the derived core
            // flow (a cold combine of multiple sources) against equal re-emissions.
            combine(
                createDashboardBasicInputsFlow(
                    repositories.selectedDate.selectedDate,
                    repositories.dailySummary,
                    repositories.settings,
                    repositories.circadian,
                    repositories.insightDismissal,
                    repositories.bodyTemperatureBaselineProvider,
                    clock,
                ),
                createDashboardCardStateFlow(
                    repositories.selectedDate.selectedDate,
                    cardManagementDelegate,
                    repositories.cardConfig,
                    repositories.dailySummary,
                    permissionChecker,
                    repositories.settings,
                ),
                createDashboardHrFlow(
                    repositories.selectedDate.selectedDate,
                    repositories.heartRate,
                    repositories.settings,
                ),
                useCases.observeDashboardStrainIncrease(
                    repositories.selectedDate.selectedDate,
                    repositories.settings.userPreferences,
                ),
                // Paired rather than passed as a 6th source: the typed `combine` overloads stop at
                // five. The ticker re-runs this transform once a minute so live residual fatigue
                // keeps decaying on a dashboard nothing else is emitting into.
                combine(
                    useCases.observeDashboardRasIncrease(
                        repositories.selectedDate.selectedDate,
                        repositories.settings.userPreferences,
                    ),
                    fatigueTicker.minuteBuckets(),
                ) { rasIncrease, minuteBucket -> rasIncrease to minuteBucket },
            ) { basicInputs, cardState, hrSummary, todayStrainIncrease, (todayRasIncrease, minuteBucket) ->
                transformToUiState(
                    basicInputs,
                    cardState,
                    minuteBucket,
                    hrSummary,
                    todayStrainIncrease,
                    todayRasIncrease,
                )
            }.distinctUntilChanged()
                .combine(createDashboardRealtimeStateFlow(foregroundSyncController)) { coreState, realtimeState ->
                    coreState.copy(
                        isRefreshing = realtimeState.isSyncing,
                        recalcProgress = realtimeState.recalcProgress,
                        isComputingMetrics = realtimeState.isSyncing && coreState.summary == null,
                    )
                }.flowOn(defaultDispatcher)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DashboardUiState(selectedDate = LocalDate.now(clock), today = LocalDate.now(clock)),
                )

        // Builds everything that depends on persisted/derived data. Realtime sync fields
        // (isRefreshing/recalcProgress/isComputingMetrics) are left at defaults here and
        // filled in by the realtime merge step above.
        private suspend fun transformToUiState(
            basicInputs: DashboardBasicInputs,
            cardState: DashboardCardState,
            minuteBucket: Long,
            hrSummary: HeartRateDaySummary? = null,
            todayStrainIncrease: Float? = null,
            todayRasIncrease: Float? = null,
        ): DashboardUiState {
            val selectedDate = basicInputs.selectedDate
            val liveResidualFatigue = resolveCurrentResidualFatigue(selectedDate, basicInputs, minuteBucket)
            val sessionSummary =
                resolveDashboardSleepSessionSummary(
                    session = cardState.lastSleepSession,
                )

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
                // isRefreshing / recalcProgress / isComputingMetrics are populated by the
                // realtime merge step; left at defaults here.
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

        // The combine transform above runs on every raw emission of any of its source flows
        // (including high-frequency ones like hrSummary), upstream of the distinctUntilChanged
        // that filters the final UiState. Without this memo, every one of those ticks would
        // re-run computeCurrentResidualFatigue's unbounded workout-table scan even when nothing
        // fatigue-relevant changed.
        //
        // minuteBucket is what makes the value actually decay: the result is a function of *now*,
        // so a key of (date, prefs, summary) alone would pin the card to whatever it read when the
        // dashboard opened — an idle dashboard emits no new summary, so the key would never move.
        // Bucketing to the minute keeps the memo effective against the high-frequency flows while
        // still letting the ticker through.
        private var lastFatigueCacheKey: FatigueCacheKey? = null
        private var lastFatigueValue: LiveResidualFatigue = LiveResidualFatigue.NotApplicable

        private suspend fun resolveCurrentResidualFatigue(
            selectedDate: LocalDate,
            basicInputs: DashboardBasicInputs,
            minuteBucket: Long,
        ): LiveResidualFatigue {
            val cacheKey =
                FatigueCacheKey(selectedDate, basicInputs.userPreferences, basicInputs.summary, minuteBucket)
            if (cacheKey == lastFatigueCacheKey) return lastFatigueValue
            // Runs outside GetDashboardDataUseCase's try/catch but performs an unbounded workout
            // scan, so a DB failure here would escape the combine transform and kill stateIn's
            // sharing coroutine — where the identical failure during card building degrades to an
            // errorMessage. Degrade to Unavailable rather than NotApplicable: a failed lookup is
            // unknown, and falling back to the snapshot would understate fatigue.
            val value =
                try {
                    useCases.getCurrentResidualFatigue(selectedDate, basicInputs.userPreferences.scoringZone())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.core.model.domain.util
                        .logE(TAG, e) { "Failed to resolve live residual fatigue" }
                    LiveResidualFatigue.Unavailable
                }
            lastFatigueCacheKey = cacheKey
            lastFatigueValue = value
            return value
        }

        private data class FatigueCacheKey(
            val selectedDate: LocalDate,
            val userPreferences: UserPreferences,
            val summary: DailySummary?,
            val minuteBucket: Long,
        )

        internal fun resolveDashboardSleepSessionSummary(session: SleepSessionData?): SleepSessionSummary? {
            session ?: return null
            // Biphasic days can legitimately aggregate more sleep than any single session.
            // Keep the available session-backed fallback instead of blanking dashboard cards.
            return SleepSessionSummary(
                efficiency = session.efficiency,
                startTime = session.startTime,
                endTime = session.endTime,
            )
        }

        val earliestDate: StateFlow<LocalDate?> =
            repositories.selectedDate.earliestDate
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        fun onPreviousDay() {
            viewModelScope.launch {
                repositories.selectedDate.selectPreviousDay()
            }
        }

        fun onNextDay() {
            viewModelScope.launch {
                repositories.selectedDate.selectNextDay()
            }
        }

        fun onEvent(event: DashboardEvent) {
            when (event) {
                is DashboardEvent.DateSelected ->
                    viewModelScope.launch {
                        repositories.selectedDate.updateSelectedDate(event.date)
                    }
                DashboardEvent.PreviousDay -> onPreviousDay()
                DashboardEvent.NextDay -> onNextDay()
                DashboardEvent.Refresh -> onRefresh()
                DashboardEvent.ToggleCardManagement -> toggleCardManagement()
                is DashboardEvent.DismissInsight -> {
                    viewModelScope.launch {
                        val zoneId =
                            repositories.settings.userPreferences
                                .first()
                                .scoringZone()
                        val dateMs =
                            repositories.selectedDate.selectedDate.value
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli()
                        repositories.insightDismissal.dismiss(dateMs, event.type)
                    }
                }
                DashboardEvent.RestoreInsights -> {
                    viewModelScope.launch {
                        val zoneId =
                            repositories.settings.userPreferences
                                .first()
                                .scoringZone()
                        val dateMs =
                            repositories.selectedDate.selectedDate.value
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli()
                        repositories.insightDismissal.restoreAllForDate(dateMs)
                    }
                }
                DashboardEvent.RequestDailyPromptCopy -> {
                    viewModelScope.launch {
                        try {
                            val zoneId =
                                repositories.settings.userPreferences
                                    .first()
                                    .scoringZone()
                            val text = generateDailyPrompt(LocalDate.now(clock.withZone(zoneId)))
                            _dailyPromptText.value = PromptRequest(text, promptRequestSeq++)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            app.readylytics.health.core.model.domain.util
                                .logE(TAG, e) { "Failed to generate daily prompt" }
                            _errorMessage.value = UiText.StringRes(R.string.ai_recommendation_copy_failed)
                        }
                    }
                }
            }
        }

        internal suspend fun generateDailyPrompt(today: LocalDate): String =
            withContext(defaultDispatcher) {
                DailyPromptFormatter.format(useCases.getDailyPromptData.execute(today))
            }

        fun onRefresh() {
            viewModelScope.launch {
                try {
                    // Pull-to-refresh recalculates the current day only; the Settings
                    // "Resync Health Connect data" button drives the full historical resync.
                    foregroundSyncController.triggerDailySync()
                } catch (e: Exception) {
                    app.readylytics.health.core.model.domain.util
                        .logE(TAG, e) { "Refresh failed" }
                    _errorMessage.value = UiText.StringRes(CoreUiR.string.error_sync_failed)
                } finally {
                    // Always clear cached derived metrics, even if the sync failed partway, so the
                    // dashboard never serves stale sleep/load scores from a previous recalculation.
                    repositories.dailyMetricCache.invalidate()
                }
            }
        }

        private val _errorMessage = MutableStateFlow<UiText?>(null)
        val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

        private var promptRequestSeq = 0
        private val _dailyPromptText = MutableStateFlow<PromptRequest?>(null)
        val dailyPromptText: StateFlow<PromptRequest?> = _dailyPromptText.asStateFlow()

        fun clearDailyPromptText() {
            _dailyPromptText.value = null
        }

        companion object {
            internal const val TAG = "DashboardViewModel"
        }
    }

private fun deriveInsights(
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

private fun resolveYesterdayMetrics(
    basicInputs: DashboardBasicInputs,
    selectedDate: LocalDate,
) = basicInputs.rasSummaries
    .firstOrNull { it.date == selectedDate.minusDays(1) }
    ?.let { DailyMetricsMapper.toMetrics(it, basicInputs.userPreferences) }

/** A single "copy today's prompt" request, made distinguishable by a monotonic [requestId]. */
data class PromptRequest(
    val text: String,
    val requestId: Int,
)

@Immutable
data class DashboardUiState(
    val summary: DailySummary? = null,
    val selectedDate: LocalDate = LocalDate.ofEpochDay(0),
    val today: LocalDate = LocalDate.ofEpochDay(0),
    val cardDataMap: Map<CardId, UniversalMetricPresentation> = emptyMap(),
    val circadianConsistency: CircadianConsistencyResult? = null,
    val restingHrCard: UniversalMetricPresentation? = null,
    val rasDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val stepCount: Int? = null,
    val stepGoal: Int = 10000,
    val lastSleepSession: SleepSessionSummary? = null,
    val cardConfigurations: List<CardConfiguration> = emptyList(),
    val isManagingCards: Boolean = false,
    val isRefreshing: Boolean = false,
    val recalcProgress: RecalcProgress? = null,
    val isComputingMetrics: Boolean = false,
    val isCalibrating: Boolean = false,
    val errorMessage: String? = null,
    val heartRateDaySummary: HeartRateDaySummary? = null,
    val activeInsightTypes: Set<InsightType> = emptySet(),
    val currentInsight: InsightType? = null,
    val currentInsightParams: InsightParams = InsightParams.None,
    val visibleInsightQueue: List<InsightType> = emptyList(),
    val dismissedInsightCount: Int = 0,
    val goalSleepHours: Float = 8f,
    val userPreferences: UserPreferences = UserPreferences(),
    val yesterdaySleepScoreRounded: Int? = null,
    val yesterdayReadiness: Float? = null,
)

/**
 * The exact subset of [DashboardUiState] that `buildCardDataMap` reads. Used as a single
 * `remember` key on the dashboard so the card map is rebuilt only when card-relevant content
 * changes — never on high-frequency sync ticks (isRefreshing/recalcProgress), and without the
 * per-recomposition `Any?[]` allocation a multi-key vararg `remember` would incur.
 *
 * Fields derivable from others are intentionally omitted: restingHrCard/stepCount/stepGoal/
 * goalSleepHours are covered by cardDataMap/summary/userPreferences. heartRateDaySummary,
 * circadianConsistency and the insight fields are kept because the cards read them directly and
 * they are NOT part of the ViewModel-computed cardDataMap.
 */
@Immutable
data class DashboardCardInputs(
    val cardDataMap: Map<CardId, UniversalMetricPresentation>,
    val summary: DailySummary?,
    val circadianConsistency: CircadianConsistencyResult?,
    val heartRateDaySummary: HeartRateDaySummary?,
    val selectedDate: LocalDate,
    val userPreferences: UserPreferences,
    val activeInsightTypes: Set<InsightType>,
    val currentInsight: InsightType?,
    val currentInsightParams: InsightParams,
    val dismissedInsightCount: Int,
    val yesterdaySleepScoreRounded: Int?,
    val yesterdayReadiness: Float?,
    val isManagingCards: Boolean,
    val isComputingMetrics: Boolean,
)

fun DashboardUiState.cardInputs(): DashboardCardInputs =
    DashboardCardInputs(
        cardDataMap = cardDataMap,
        summary = summary,
        circadianConsistency = circadianConsistency,
        heartRateDaySummary = heartRateDaySummary,
        selectedDate = selectedDate,
        userPreferences = userPreferences,
        activeInsightTypes = activeInsightTypes,
        currentInsight = currentInsight,
        currentInsightParams = currentInsightParams,
        dismissedInsightCount = dismissedInsightCount,
        yesterdaySleepScoreRounded = yesterdaySleepScoreRounded,
        yesterdayReadiness = yesterdayReadiness,
        isManagingCards = isManagingCards,
        isComputingMetrics = isComputingMetrics,
    )

@Immutable
data class CardData(
    val title: String,
    val value: String,
    val unit: String,
    val status: MetricStatus,
    val tooltip: String,
    val action: DashboardAction? = null,
    val secondaryText: String? = null,
)

enum class DashboardAction {
    NAVIGATE_SLEEP,
    NAVIGATE_WORKOUTS,
    NAVIGATE_RHR,
    NAVIGATE_STEPS,
    NAVIGATE_WEIGHT,
    NAVIGATE_BODY_FAT,
    NAVIGATE_BLOOD_PRESSURE,
    NAVIGATE_VITALS,
}
