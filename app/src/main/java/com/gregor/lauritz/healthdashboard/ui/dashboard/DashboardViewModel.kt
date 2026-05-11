package com.gregor.lauritz.healthdashboard.ui.dashboard

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardManagementDelegate
import com.gregor.lauritz.healthdashboard.domain.dashboard.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.dashboard.GetDashboardDataUseCase
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private data class DashboardInputs(
    val summary: DailySummary?,
    val prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences,
    val circadian: CircadianConsistencyResult?,
    val paiSummaries: List<DailySummary>,
)

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
        circadianRepo: CircadianConsistencyRepository,
    ) : ViewModel() {

        private val cardManagementDelegate = CardManagementDelegate(cardConfigRepository, viewModelScope)

        val today: StateFlow<LocalDate> = selectedDateRepository.selectedDate
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LocalDate.now(),
            )

        val isManagingCards: StateFlow<Boolean> = cardManagementDelegate.isManagingCards

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    val today = LocalDate.now()
                    val zoneId = ZoneId.systemDefault()
                    val summaryFlow =
                        if (date == today) {
                            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                            dailySummaryRepository.observeSince(todayMs).map { it.firstOrNull() }
                        } else {
                            val midnightMs =
                                date
                                    .atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            dailySummaryRepository.observeByDate(midnightMs)
                        }
                    val paiFromMs = date.minusDays(6)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val paiBreakdownFlow = dailySummaryRepository.observeSince(paiFromMs)
                    val sessionFlow = dailySummaryRepository.observeFirstSessionEndingInRange(
                        fromMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                        toMs = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    )
                    val basicInputsFlow = combine(
                        summaryFlow,
                        settingsRepo.userPreferences,
                        circadianRepo.resultFor(date),
                        paiBreakdownFlow,
                    ) { summary, prefs, circadian, paiSummaries ->
                        DashboardInputs(summary, prefs, circadian, paiSummaries)
                    }

                    combine(
                        basicInputsFlow,
                        cardManagementDelegate.isManagingCards,
                        cardConfigRepository.dashboardCardConfigurations(),
                        sessionFlow,
                        foregroundSyncController.isSyncing,
                    ) { inputs, isManaging, cardConfigs, session, isSyncing ->
                        val cards = getDashboardDataUseCase.invoke(
                            summary = inputs.summary,
                            prefs = inputs.prefs,
                            date = date,
                            lastSleepSession = session,
                            paiSummaries = inputs.paiSummaries,
                        )
                        DashboardUiState(
                            summary = inputs.summary,
                            selectedDate = date,
                            cardDataMap = cards.cardDataMap,
                            circadianConsistency = inputs.circadian,
                            restingHrCard = cards.cardDataMap[CardId.RESTING_HR],
                            paiDailyBreakdown = cards.paiDailyBreakdown,
                            stepCount = inputs.summary?.stepCount,
                            stepGoal = inputs.prefs.stepGoal,
                            lastSleepSession = session,
                            cardConfigurations = cardConfigs,
                            isManagingCards = isManaging,
                            isRefreshing = isSyncing,
                            isCalibrating = inputs.summary?.isCalibrating ?: false,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DashboardUiState(),
                )

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

        fun onToggleCardVisibility(cardId: CardId, visible: Boolean) {
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

        fun onRefresh() {
            viewModelScope.launch {
                try {
                    foregroundSyncController.triggerImmediateSync()
                } catch (e: Exception) {
                    Log.e(TAG, "Refresh failed", e)
                }
            }
        }

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
    val lastSleepSession: SleepSessionEntity? = null,
    val cardConfigurations: List<CardConfiguration> = emptyList(),
    val isManagingCards: Boolean = false,
    val isRefreshing: Boolean = false,
    val isCalibrating: Boolean = false,
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
