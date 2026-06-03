package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailyMetrics
import com.gregor.lauritz.healthdashboard.domain.model.DailyMetricsMapper
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class Baselines(
    val hrv: Float? = null,
    val rhr: Int? = null,
)

data class SleepUiState(
    val latestSummary: DailySummary? = null,
    val latestMetrics: DailyMetrics? = null,
    val latestSession: SleepSessionData? = null,
    val stageTimeline: List<SleepStageData> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class SleepViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val sleepSessionRepository: SleepSessionRepository,
        private val heartRateRepository: HeartRateRepository,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val circadianRepo: CircadianConsistencyRepository,
        private val foregroundSyncController: ForegroundSyncController,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        @OptIn(ExperimentalCoroutinesApi::class)
        val circadianConsistencyFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    circadianRepo.resultFor(date)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = CircadianConsistencyResult.Calibrating,
                )

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    val zoneId = ZoneId.systemDefault()
                    val selectedMidnightMs =
                        date
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    val nextDayMidnightMs =
                        date
                            .plusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    val summaryFlow =
                        if (date == LocalDate.now(zoneId)) {
                            val todayMs =
                                LocalDate
                                    .now(zoneId)
                                    .atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli()
                            dailySummaryRepository
                                .observeSince(todayMs)
                                .map { it.firstOrNull() }
                        } else {
                            flow {
                                emit(
                                    dailySummaryRepository
                                        .getByDate(
                                            selectedMidnightMs,
                                        ),
                                )
                            }
                        }

                    val sessionFlow =
                        sleepSessionRepository.observeFirstSessionEndingInRange(
                            selectedMidnightMs,
                            nextDayMidnightMs,
                        )

                    val stagesFlow =
                        sessionFlow.flatMapLatest { session ->
                            if (session == null) {
                                flowOf(emptyList())
                            } else {
                                sleepSessionRepository.observeSessionStages(session.id)
                            }
                        }

                    combine(
                        summaryFlow,
                        sessionFlow,
                        stagesFlow,
                        foregroundSyncController.isSyncing,
                        settingsRepo.userPreferences,
                    ) { latestSummary, latestSession, stages, isSyncing, prefs ->
                        SleepUiState(
                            latestSummary = latestSummary,
                            latestMetrics = latestSummary?.let { DailyMetricsMapper.toMetrics(it, prefs) },
                            latestSession = latestSession,
                            stageTimeline = stages,
                            selectedDate = date,
                            isLoading = isSyncing,
                        )
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SleepUiState(isLoading = true),
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
    }
