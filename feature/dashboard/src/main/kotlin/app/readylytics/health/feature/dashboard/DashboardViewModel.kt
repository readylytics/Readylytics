package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.domain.cache.DailyMetricCache
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.dashboard.CardManagementEvent
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.model.DailyMetricsMapper
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.InsightDismissalRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.scoring.domain.airecommendation.DailyPromptFormatter
import app.readylytics.health.core.scoring.domain.airecommendation.GetDailyPromptDataUseCase
import app.readylytics.health.core.scoring.domain.dashboard.DerivedInsights
import app.readylytics.health.core.scoring.domain.dashboard.InsightDeriver
import app.readylytics.health.core.scoring.domain.insights.InsightContext
import app.readylytics.health.core.scoring.domain.insights.InsightEngine
import app.readylytics.health.core.scoring.domain.insights.InsightParams
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.common.BaseViewModel
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.feature.dashboard.usecase.GetCurrentResidualFatigueUseCase
import app.readylytics.health.feature.dashboard.usecase.GetDashboardDataUseCase
import app.readylytics.health.feature.dashboard.usecase.LiveResidualFatigue
import app.readylytics.health.feature.dashboard.usecase.ObserveDashboardRasIncreaseUseCase
import app.readylytics.health.feature.dashboard.usecase.ObserveDashboardStrainIncreaseUseCase
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
import java.time.LocalTime
import javax.inject.Inject
import app.readylytics.health.core.ui.R as CoreUiR

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val getDashboardDataUseCase: GetDashboardDataUseCase,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val selectedDateRepository: SelectedDateStore,
        private val settingsRepo: UserPreferencesReader,
        private val cardConfigRepository: CardConfigurationRepository,
        private val circadianRepo: CircadianConsistencyRepository,
        private val dailyMetricCache: DailyMetricCache,
        private val heartRateRepository: HeartRateRepository,
        private val insightDismissalRepository: InsightDismissalRepository,
        private val observeDashboardStrainIncreaseUseCase: ObserveDashboardStrainIncreaseUseCase,
        private val observeDashboardRasIncreaseUseCase: ObserveDashboardRasIncreaseUseCase,
        private val getDailyPromptDataUseCase: GetDailyPromptDataUseCase,
        private val getCurrentResidualFatigueUseCase: GetCurrentResidualFatigueUseCase,
        private val fatigueTicker: DashboardFatigueTicker,
        private val bodyTemperatureBaselineProvider: BodyTemperatureBaselineProvider,
        private val healthConnectRepository: HealthConnectRepository,
        private val clock: Clock,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : BaseViewModel() {
        fun validateSelectedDate(date: LocalDate): Result<LocalDate> =
            if (date <= LocalDate.now(clock)) {
                Result.success(date)
            } else {
                Result.failure("Cannot select future dates", "INVALID_DATE")
            }

        private val cardManagementDelegate =
            CardManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                persist = cardConfigRepository::updateDashboardCardConfigurations,
                scope = viewModelScope,
                hasBodyTemperaturePermission = { healthConnectRepository.hasBodyTemperaturePermission() },
                hasStepsPermission = { healthConnectRepository.hasStepsPermission() },
                hasWeightPermission = { healthConnectRepository.hasWeightPermission() },
                hasBodyFatPermission = { healthConnectRepository.hasBodyFatPermission() },
                hasBloodPressurePermission = { healthConnectRepository.hasBloodPressurePermission() },
                hasOxygenSaturationPermission = { healthConnectRepository.hasOxygenSaturationPermission() },
            )

        val isManagingCards: StateFlow<Boolean> = cardManagementDelegate.isManagingCards

        val uiState: StateFlow<DashboardUiState> =
            // The expensive transform (InsightEngine + GetDashboardDataUseCase) is driven only
            // by the data flows (basic/card/hr). Realtime sync state is merged in afterwards via
            // a cheap copy, so recalcProgress/isSyncing ticks during a resync no longer re-run
            // insight evaluation and card building. distinctUntilChanged guards the derived core
            // flow (a cold combine of multiple sources) against equal re-emissions.
            combine(
                createDashboardBasicInputsFlow(
                    selectedDateRepository.selectedDate,
                    dailySummaryRepository,
                    settingsRepo,
                    circadianRepo,
                    insightDismissalRepository,
                    bodyTemperatureBaselineProvider,
                ),
                createDashboardCardStateFlow(
                    selectedDateRepository.selectedDate,
                    cardManagementDelegate,
                    cardConfigRepository,
                    dailySummaryRepository,
                    healthConnectRepository,
                ),
                createDashboardHrFlow(selectedDateRepository.selectedDate, heartRateRepository),
                observeDashboardStrainIncreaseUseCase(
                    selectedDateRepository.selectedDate,
                    settingsRepo.userPreferences,
                ),
                // Paired rather than passed as a 6th source: the typed `combine` overloads stop at
                // five. The ticker re-runs this transform once a minute so live residual fatigue
                // keeps decaying on a dashboard nothing else is emitting into.
                combine(
                    observeDashboardRasIncreaseUseCase(
                        selectedDateRepository.selectedDate,
                        settingsRepo.userPreferences,
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
                    initialValue = DashboardUiState(),
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
                getDashboardDataUseCase.invoke(
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
            val derived = deriveInsights(basicInputs, selectedDate)
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
                    getCurrentResidualFatigueUseCase(selectedDate, basicInputs.userPreferences.scoringZone())
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

        private fun deriveInsights(
            basicInputs: DashboardBasicInputs,
            selectedDate: LocalDate,
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
                            nowMinutesOfDay = nowMinutesOfDayFor(selectedDate),
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

        // Time-of-day gating for insights only makes sense for the current day;
        // for past days, treat as end-of-day so it never suppresses a finding.
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

        private fun nowMinutesOfDayFor(selectedDate: LocalDate): Int =
            if (selectedDate == LocalDate.now(clock)) {
                LocalTime.now(clock).let { it.hour * 60 + it.minute }
            } else {
                1439
            }

        val earliestDate: StateFlow<LocalDate?> =
            selectedDateRepository.earliestDate
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        fun onPreviousDay() {
            viewModelScope.launch {
                selectedDateRepository.selectPreviousDay()
            }
        }

        fun onNextDay() {
            viewModelScope.launch {
                selectedDateRepository.selectNextDay()
            }
        }

        fun toggleCardManagement() {
            if (isManagingCards.value) {
                cardManagementDelegate.saveChanges()
            } else {
                cardManagementDelegate.enterEditMode(uiState.value.cardConfigurations)
            }
        }

        fun onCancelCardManagement() {
            cardManagementDelegate.cancelChanges()
        }

        fun onToggleCardVisibility(
            cardId: CardId,
            visible: Boolean,
        ) {
            cardManagementDelegate.onToggleCardVisibility(
                uiState.value.cardConfigurations,
                cardId,
                visible,
            )
        }

        fun onReorderCards(newOrder: List<CardConfiguration>) {
            cardManagementDelegate.onReorderCards(
                uiState.value.cardConfigurations,
                newOrder,
            )
        }

        fun onResetToDefaults() {
            cardManagementDelegate.onResetToDefaults()
        }

        fun onCardDisplayModeChanged(
            cardId: CardId,
            mode: DashboardCardDisplayMode,
        ) {
            cardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))
        }

        fun onEvent(event: DashboardEvent) {
            when (event) {
                is DashboardEvent.DateSelected ->
                    viewModelScope.launch {
                        selectedDateRepository.updateSelectedDate(event.date)
                    }
                DashboardEvent.PreviousDay -> onPreviousDay()
                DashboardEvent.NextDay -> onNextDay()
                DashboardEvent.Refresh -> onRefresh()
                DashboardEvent.ToggleCardManagement -> toggleCardManagement()
                is DashboardEvent.DismissInsight -> {
                    viewModelScope.launch {
                        val zoneId = settingsRepo.userPreferences.first().scoringZone()
                        val dateMs =
                            selectedDateRepository.selectedDate.value
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli()
                        insightDismissalRepository.dismiss(dateMs, event.type)
                    }
                }
                DashboardEvent.RestoreInsights -> {
                    viewModelScope.launch {
                        val zoneId = settingsRepo.userPreferences.first().scoringZone()
                        val dateMs =
                            selectedDateRepository.selectedDate.value
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli()
                        insightDismissalRepository.restoreAllForDate(dateMs)
                    }
                }
                DashboardEvent.RequestDailyPromptCopy -> {
                    viewModelScope.launch {
                        try {
                            val zoneId = settingsRepo.userPreferences.first().scoringZone()
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
                DailyPromptFormatter.format(getDailyPromptDataUseCase.execute(today))
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
                    dailyMetricCache.invalidate()
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

/** A single "copy today's prompt" request, made distinguishable by a monotonic [requestId]. */
data class PromptRequest(
    val text: String,
    val requestId: Int,
)

@Immutable
data class DashboardUiState(
    val summary: DailySummary? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
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
