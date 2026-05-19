package com.gregor.lauritz.healthdashboard.ui.dashboard

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.cache.DailyMetricCache
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardManagementDelegate
import com.gregor.lauritz.healthdashboard.domain.dashboard.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.dashboard.GetDashboardDataUseCase
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.SleepSessionSummary
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    ) : ViewModel() {
        private val cardManagementDelegate = CardManagementDelegate(cardConfigRepository, viewModelScope)

        val isManagingCards: StateFlow<Boolean> = cardManagementDelegate.isManagingCards

        val uiState: StateFlow<DashboardUiState> =
            combine(
                createDashboardBasicInputsFlow(
                    selectedDateRepository.selectedDate,
                    dailySummaryRepository,
                    settingsRepo,
                    circadianRepo,
                ),
                createDashboardCardStateFlow(
                    selectedDateRepository.selectedDate,
                    cardManagementDelegate,
                    cardConfigRepository,
                    dailySummaryRepository,
                ),
                createDashboardRealtimeStateFlow(foregroundSyncController),
            ) { basicInputs, cardState, realtimeState ->
                val combined =
                    DashboardCombinedInputs(
                        basicInputs = basicInputs,
                        cardState = cardState,
                        realtimeState = realtimeState,
                    )
                transformToUiState(combined, basicInputs.selectedDate)
            }.flowOn(Dispatchers.Default).stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState(),
            )

        private fun transformToUiState(
            combined: DashboardCombinedInputs,
            selectedDate: LocalDate,
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

            val cards =
                getDashboardDataUseCase.invoke(
                    summary = basicInputs.summary,
                    prefs = basicInputs.userPreferences,
                    date = selectedDate,
                    lastSleepSession = sessionSummary,
                    paiSummaries = basicInputs.paiSummaries,
                )

            return DashboardUiState(
                summary = basicInputs.summary,
                selectedDate = selectedDate,
                cardDataMap = cards.cardDataMap,
                circadianConsistency = basicInputs.circadianResult,
                restingHrCard = cards.cardDataMap[CardId.RESTING_HR],
                paiDailyBreakdown = cards.paiDailyBreakdown,
                stepCount = basicInputs.summary?.stepCount,
                stepGoal = basicInputs.userPreferences.stepGoal,
                lastSleepSession = sessionSummary,
                cardConfigurations = cardState.cardConfiguration,
                isManagingCards = cardState.isManagingCards,
                isRefreshing = realtimeState.isSyncing,
                isComputingMetrics = realtimeState.isSyncing && basicInputs.summary == null,
                isCalibrating = basicInputs.summary?.isCalibrating ?: false,
            )
        }

        fun formatSleepDuration(minutes: Int?): String = getDashboardDataUseCase.formatSleepDuration(minutes)

        fun onPreviousDay() {
            selectedDateRepository.selectPreviousDay()
        }

        fun onNextDay() {
            selectedDateRepository.selectNextDay()
        }

        fun toggleCardManagement() {
            cardManagementDelegate.toggleCardManagement()
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
                is DashboardEvent.DateSelected -> selectedDateRepository.updateSelectedDate(event.date)
                DashboardEvent.PreviousDay -> onPreviousDay()
                DashboardEvent.NextDay -> onNextDay()
                DashboardEvent.Refresh -> onRefresh()
                DashboardEvent.ToggleCardManagement -> toggleCardManagement()
            }
        }

        fun onRefresh() {
            viewModelScope.launch {
                try {
                    foregroundSyncController.triggerImmediateSync()
                    dailyMetricCache.invalidate()
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh failed", e)
                    _errorMessage.value = e.message ?: "Sync failed"
                }
            }
        }

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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
    val isComputingMetrics: Boolean = false,
    val isCalibrating: Boolean = false,
    val errorMessage: String? = null,
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
}
