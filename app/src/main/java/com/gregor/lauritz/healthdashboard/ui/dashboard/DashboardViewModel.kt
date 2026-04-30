package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class DashboardUiState(
    val summary: DailySummary? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val isRefreshing: Boolean = false,
    val cardData: List<CardData> = emptyList(),
    val cardRows: List<List<CardData>> = emptyList(),
    val circadianConsistency: CircadianConsistencyResult? = null,
    val restingHrCard: CardData? = null,
    val paiDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val stepCount: Int? = null,
    val stepGoal: Int = 10000,
    val lastSleepSession: SleepSessionEntity? = null,
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

private data class DashboardInputs(
    val summary: DailySummary?,
    val prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences,
    val isRefreshing: Boolean,
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
        prefsRepo: UserPreferencesRepository,
        circadianRepo: CircadianConsistencyRepository,
    ) : ViewModel() {
        private val _isRefreshing = MutableStateFlow(false)

        val today: StateFlow<LocalDate> = selectedDateRepository.selectedDate
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LocalDate.now(),
            )

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
                    combine(
                        combine(
                            summaryFlow,
                            prefsRepo.userPreferences,
                            _isRefreshing,
                            circadianRepo.resultFor(date),
                            paiBreakdownFlow
                        ) { summary, prefs, refreshing, circadian, paiSummaries ->
                            DashboardInputs(summary, prefs, refreshing, circadian, paiSummaries)
                        },
                        sessionFlow
                    ) { inputs, session ->
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
                            isRefreshing = inputs.isRefreshing,
                            cardData = cards.mainCards,
                            cardRows = cards.mainCards.chunked(2),
                            circadianConsistency = inputs.circadian,
                            restingHrCard = cards.restingHrCard,
                            paiDailyBreakdown = cards.paiDailyBreakdown,
                            stepCount = inputs.summary?.stepCount,
                            stepGoal = inputs.prefs.stepGoal,
                            lastSleepSession = session,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DashboardUiState(),
                )

        fun formatSleepDuration(minutes: Int?): String = getDashboardDataUseCase.formatSleepDuration(minutes)

        fun onRefresh() = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                foregroundSyncController.triggerImmediateSync()
            } catch (_: Exception) {
                // Sync errors are non-fatal for the UI — the state will update once data arrives
            } finally {
                _isRefreshing.value = false
            }
        }

        fun onPreviousDay() {
            selectedDateRepository.selectPreviousDay()
        }

        fun onNextDay() {
            selectedDateRepository.selectNextDay()
        }
    }
