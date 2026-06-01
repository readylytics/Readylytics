package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.domain.model.hrvZoneBands
import com.gregor.lauritz.healthdashboard.domain.model.rhrZoneBands
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.common.padToRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class Baselines(
    val hrv: Float? = null,
    val rhr: Int? = null,
)

data class SleepUiState(
    val latestSummary: DailySummary? = null,
    val latestSession: SleepSessionData? = null,
    val dailyHrv: List<DailyDataPoint> = emptyList(),
    val dailyRhr: List<DailyDataPoint> = emptyList(),
    val hrvBaseline: Float? = null,
    val rhrBaseline: Float? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val goalSleepMinutes: Int = 480,
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val hrvZoneBands: List<ZoneBand>? = null,
    val rhrZoneBands: List<ZoneBand>? = null,
)

private data class SleepData(
    val latestSummary: DailySummary?,
    val lastSession: SleepSessionData?,
    val summaries: List<DailySummary>,
    val prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences,
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
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        /**
         * Combined baselines (HRV & RHR calculated from past 30 days, constant across all views).
         * Single subscription to selectedDate reduces flow overhead.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val baselinesFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    val selectedMidnightMs = date.toMidnightEpochMilli()
                    combine(
                        dailySummaryRepository.observeByDate(selectedMidnightMs),
                        settingsRepo.userPreferences,
                    ) { latestSummary, prefs ->
                        val hrvBaseline =
                            latestSummary?.hrvBaseline?.toFloat()
                                ?: prefs.hrvBaselineOverride

                        val ratio = latestSummary?.rhrRatio
                        val rhr = latestSummary?.nocturnalRhr
                        val rhrBaseline =
                            if (ratio != null && ratio > 0f && rhr != null) {
                                (rhr / ratio).toInt()
                            } else {
                                latestSummary?.restingHrBaseline ?: prefs.rhrBaselineOverride?.toInt()
                                    ?: ScoringConstants.DEFAULT_RHR_BPM.toInt()
                            }

                        Baselines(hrvBaseline, rhrBaseline)
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = Baselines(),
                )

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
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
            ) { range, date -> range to date }
                .flatMapLatest { (range, date) ->
                    val fromMs = range.fromMs(date)
                    val startDayMs = fromMs.truncateToDayMs()
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

                    val dataFlow =
                        combine(
                            summaryFlow,
                            sleepSessionRepository.observeFirstSessionEndingInRange(
                                selectedMidnightMs,
                                nextDayMidnightMs,
                            ),
                            dailySummaryRepository.observeSince(fromMs),
                            settingsRepo.userPreferences,
                        ) { latestSummary, latestSession, summaries, prefs ->
                            SleepData(latestSummary, latestSession, summaries, prefs)
                        }

                    combine(
                        dataFlow,
                        baselinesFlow,
                        foregroundSyncController.isSyncing,
                    ) { data, baselines, isSyncing ->
                        val (latestSummary, latestSession, summaries, prefs) = data
                        val (bHrv, bRhr) = baselines

                        val startLocalDate = Instant.ofEpochMilli(startDayMs).atZone(zoneId).toLocalDate()

                        val hrvPoints =
                            summaries
                                .mapNotNull { s ->
                                    s.nocturnalHrv?.let { hrv ->
                                        val d = s.date
                                        DailyDataPoint(
                                            dayOffset = ChronoUnit.DAYS.between(startLocalDate, d).toInt(),
                                            value = hrv.toFloat(),
                                        )
                                    }
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)
                        val rhrPoints =
                            summaries
                                .mapNotNull { s ->
                                    s.nocturnalRhr?.let { rhr ->
                                        val d = s.date
                                        DailyDataPoint(
                                            dayOffset = ChronoUnit.DAYS.between(startLocalDate, d).toInt(),
                                            value = rhr.toFloat(),
                                        )
                                    }
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        val baselineHrv = bHrv ?: calculateMedian(hrvPoints)
                        val baselineRhr = bRhr?.toFloat() ?: calculateMedian(rhrPoints)

                        val hrvBands =
                            baselineHrv?.let { baseline ->
                                hrvZoneBands(
                                    optimalMin = prefs.hrvOptimalThreshold * baseline,
                                    neutralMin = prefs.hrvWarningThreshold * baseline,
                                    warningMin = (2f * prefs.hrvWarningThreshold - 1f) * baseline,
                                )
                            }

                        val rhrBands =
                            baselineRhr?.let { baseline ->
                                rhrZoneBands(
                                    optimalMax = prefs.rhrOptimalThreshold * baseline,
                                    neutralMax = prefs.rhrWarningThreshold * baseline,
                                    warningMax = prefs.rhrWarningThreshold * 1.3f * baseline,
                                )
                            }

                        SleepUiState(
                            latestSummary = latestSummary,
                            latestSession = latestSession,
                            dailyHrv = hrvPoints,
                            dailyRhr = rhrPoints,
                            hrvBaseline = baselineHrv,
                            rhrBaseline = baselineRhr,
                            goalSleepMinutes = (prefs.goalSleepHours * 60).toInt(),
                            selectedRange = range,
                            selectedDate = date,
                            rangeStartMs = startDayMs,
                            isLoading = isSyncing,
                            hrvZoneBands = hrvBands,
                            rhrZoneBands = rhrBands,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SleepUiState(),
                )

        private fun calculateMedian(points: List<DailyDataPoint>): Float? {
            val values = points.mapNotNull { it.value }
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
            savedStateHandle["selectedRange"] = range
        }

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
