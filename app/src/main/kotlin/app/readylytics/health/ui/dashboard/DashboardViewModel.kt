package app.readylytics.health.ui.dashboard

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import app.readylytics.health.R
import app.readylytics.health.data.preferences.CardConfigurationRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.di.DefaultDispatcher
import app.readylytics.health.domain.cache.DailyMetricCache
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.CardManagementDelegate
import app.readylytics.health.domain.dashboard.GetDashboardDataUseCase
import app.readylytics.health.domain.dashboard.InsightDeriver
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
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
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
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
                createDashboardRealtimeStateFlow(foregroundSyncController),
                createDashboardHrFlow(selectedDateRepository.selectedDate, heartRateRepository),
            ) { basicInputs, cardState, realtimeState, hrSummary ->
                val combined =
                    DashboardCombinedInputs(
                        basicInputs = basicInputs,
                        cardState = cardState,
                        realtimeState = realtimeState,
                    )
                transformToUiState(combined, basicInputs.selectedDate, hrSummary)
            }.flowOn(defaultDispatcher).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState(),
            )

        private fun transformToUiState(
            combined: DashboardCombinedInputs,
            selectedDate: LocalDate,
            hrSummary: HeartRateDaySummary? = null,
        ): DashboardUiState {
            val basicInputs = combined.basicInputs
            val cardState = combined.cardState
            val realtimeState = combined.realtimeState

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
                    paiSummaries = basicInputs.paiSummaries,
                )

            val cards = cardsResult.getOrNull()
            val derived =
                InsightDeriver.derive(
                    recoveryFlags = basicInputs.summary?.recoveryFlags,
                    dismissedTypes = basicInputs.dismissedInsightTypes,
                )
            return DashboardUiState(
                summary = basicInputs.summary,
                selectedDate = selectedDate,
                cardDataMap = cards?.cardDataMap ?: emptyMap(),
                circadianConsistency = basicInputs.circadianResult,
                restingHrCard = cards?.cardDataMap?.get(CardId.RESTING_HR),
                paiDailyBreakdown = cards?.paiDailyBreakdown ?: emptyList(),
                stepCount = basicInputs.summary?.stepCount,
                stepGoal = basicInputs.userPreferences.stepGoal,
                lastSleepSession = sessionSummary,
                cardConfigurations = cardState.pendingConfiguration ?: cardState.cardConfiguration,
                isManagingCards = cardState.isManagingCards,
                isRefreshing = realtimeState.isSyncing,
                recalcProgress = realtimeState.recalcProgress,
                isComputingMetrics = realtimeState.isSyncing && basicInputs.summary == null,
                isCalibrating = basicInputs.summary?.isCalibrating ?: false,
                errorMessage = if (cardsResult.isFailure) "Failed to load dashboard data" else null,
                heartRateDaySummary = hrSummary,
                activeInsightTypes = derived.active,
                currentInsight = derived.current,
                visibleInsightQueue = derived.visibleQueue,
                dismissedInsightCount = derived.dismissedCount,
                goalSleepHours = basicInputs.userPreferences.goalSleepHours,
            )
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
                    Log.e(TAG, "Refresh failed", e)
                    _errorMessage.value = e.message?.let { UiText.RawString(it) }
                        ?: UiText.StringRes(R.string.error_sync_failed)
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
    val cardDataMap: Map<CardId, CardData> = emptyMap(),
    val circadianConsistency: CircadianConsistencyResult? = null,
    val restingHrCard: CardData? = null,
    val paiDailyBreakdown: List<Pair<String, Float>> = emptyList(),
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
    val visibleInsightQueue: List<InsightType> = emptyList(),
    val dismissedInsightCount: Int = 0,
    val goalSleepHours: Float = 8f,
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
