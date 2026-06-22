package app.readylytics.health.ui.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import app.readylytics.health.R
import app.readylytics.health.data.preferences.CardConfigurationRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.cache.DailyMetricCache
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.CardManagementDelegate
import app.readylytics.health.domain.dashboard.GetDashboardDataUseCase
import app.readylytics.health.domain.dashboard.InsightDeriver
import app.readylytics.health.domain.insights.InsightContext
import app.readylytics.health.domain.insights.InsightEngine
import app.readylytics.health.domain.insights.InsightParams
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.RecalcProgress
import app.readylytics.health.ui.common.BaseViewModel
import app.readylytics.health.ui.common.UiText
import app.readylytics.health.ui.heartrate.HeartRateDaySummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val getDashboardDataUseCase: GetDashboardDataUseCase,
        private val foregroundSyncController: ForegroundSyncController,
        private val selectedDateRepository: SelectedDateRepository,
        private val settingsRepo: SettingsRepository,
        private val cardConfigRepository: CardConfigurationRepository,
        private val circadianRepo: CircadianConsistencyRepository,
        private val dailyMetricCache: DailyMetricCache,
        private val heartRateRepository: HeartRateRepository,
        private val insightDismissalRepository: InsightDismissalRepository,
        private val clock: Clock,
    ) : BaseViewModel() {
        fun validateSelectedDate(date: LocalDate): Result<LocalDate> =
            if (date <= LocalDate.now()) {
                Result.success(date)
            } else {
                Result.failure("Cannot select future dates", "INVALID_DATE")
            }

        private val cardManagementDelegate = CardManagementDelegate(cardConfigRepository, viewModelScope)

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
                ),
                createDashboardCardStateFlow(
                    selectedDateRepository.selectedDate,
                    cardManagementDelegate,
                    cardConfigRepository,
                    dailySummaryRepository,
                ),
                createDashboardHrFlow(selectedDateRepository.selectedDate, heartRateRepository),
            ) { basicInputs, cardState, hrSummary ->
                transformToUiState(basicInputs, cardState, hrSummary)
            }.distinctUntilChanged()
                .combine(createDashboardRealtimeStateFlow(foregroundSyncController)) { coreState, realtimeState ->
                    coreState.copy(
                        isRefreshing = realtimeState.isSyncing,
                        recalcProgress = realtimeState.recalcProgress,
                        isComputingMetrics = realtimeState.isSyncing && coreState.summary == null,
                    )
                }.flowOn(Dispatchers.Default).stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DashboardUiState(),
                )

        // Builds everything that depends on persisted/derived data. Realtime sync fields
        // (isRefreshing/recalcProgress/isComputingMetrics) are left at defaults here and
        // filled in by the realtime merge step above.
        private fun transformToUiState(
            basicInputs: DashboardBasicInputs,
            cardState: DashboardCardState,
            hrSummary: HeartRateDaySummary? = null,
        ): DashboardUiState {
            val selectedDate = basicInputs.selectedDate
            val sessionSummary =
                cardState.lastSleepSession?.let {
                    SleepSessionSummary(
                        efficiency = it.efficiency,
                        startTime = it.startTime,
                        endTime = it.endTime,
                    )
                }

            val cardsResult =
                getDashboardDataUseCase.invoke(
                    summary = basicInputs.summary,
                    prefs = basicInputs.userPreferences,
                    date = selectedDate,
                    lastSleepSession = sessionSummary,
                    rasSummaries = basicInputs.rasSummaries,
                )

            val cards = cardsResult.getOrNull()
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
            val derived =
                InsightDeriver.derive(
                    recoveryFlags = basicInputs.summary?.recoveryFlags,
                    engineFindings = engineFindings,
                    dismissedTypes = basicInputs.dismissedInsightTypes,
                )
            val yesterday = selectedDate.minusDays(1)
            val yesterdaySummary = basicInputs.rasSummaries.firstOrNull { it.date == yesterday }
            val yesterdayMetrics =
                yesterdaySummary?.let {
                    DailyMetricsMapper.toMetrics(it, basicInputs.userPreferences)
                }
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

        // Time-of-day gating for insights only makes sense for the current day;
        // for past days, treat as end-of-day so it never suppresses a finding.
        private fun nowMinutesOfDayFor(selectedDate: LocalDate): Int =
            if (selectedDate == LocalDate.now()) {
                LocalTime.now().let { it.hour * 60 + it.minute }
            } else {
                1439
            }

        fun formatSleepDuration(minutes: Int?): String = getDashboardDataUseCase.formatSleepDuration(minutes)

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
                    val zoneId = ZoneId.systemDefault()
                    val dateMs =
                        selectedDateRepository.selectedDate.value
                            .atStartOfDay(
                                zoneId,
                            ).toInstant()
                            .toEpochMilli()
                    viewModelScope.launch {
                        insightDismissalRepository.dismiss(dateMs, event.type)
                    }
                }
                DashboardEvent.RestoreInsights -> {
                    val zoneId = ZoneId.systemDefault()
                    val dateMs =
                        selectedDateRepository.selectedDate.value
                            .atStartOfDay(
                                zoneId,
                            ).toInstant()
                            .toEpochMilli()
                    viewModelScope.launch {
                        insightDismissalRepository.restoreAllForDate(dateMs)
                    }
                }
            }
        }

        fun onRefresh() {
            viewModelScope.launch {
                try {
                    // Pull-to-refresh recalculates the current day only; the Settings
                    // "Resync Health Connect data" button drives the full historical resync.
                    foregroundSyncController.triggerDailySync()
                } catch (e: Exception) {
                    app.readylytics.health.domain.util
                        .logE(TAG, e) { "Refresh failed" }
                    _errorMessage.value = UiText.StringRes(R.string.error_sync_failed)
                } finally {
                    // Always clear cached derived metrics, even if the sync failed partway, so the
                    // dashboard never serves stale sleep/load scores from a previous recalculation.
                    dailyMetricCache.invalidate()
                }
            }
        }

        private val _errorMessage = MutableStateFlow<UiText?>(null)
        val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

        companion object {
            internal const val TAG = "DashboardViewModel"
        }
    }

@Immutable
data class DashboardUiState(
    val summary: DailySummary? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val cardDataMap: Map<CardId, CardData> = emptyMap(),
    val circadianConsistency: CircadianConsistencyResult? = null,
    val restingHrCard: CardData? = null,
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
