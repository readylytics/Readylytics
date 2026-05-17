package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class SleepUiState(
    val latestSummary: DailySummary? = null,
    val latestSession: SleepSessionEntity? = null,
    val dailyHrv: List<DailyDataPoint> = emptyList(),
    val dailyRhr: List<DailyDataPoint> = emptyList(),
    val hrvBaseline: Float? = null,
    val rhrBaseline: Float? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val goalSleepMinutes: Int = 480,
    val rangeStartMs: Long = System.currentTimeMillis(),
)

private data class SleepData(
    val latestSummary: DailySummary?,
    val lastSession: SleepSessionEntity?,
    val summaries: List<DailySummary>,
    val prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences,
)

@HiltViewModel
class SleepViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val sleepSessionDao: SleepSessionDao,
        private val hrvDao: HrvDao,
        private val heartRateDao: HeartRateDao,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val circadianRepo: CircadianConsistencyRepository,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        /**
         * Baseline HRV (calculated from past 30 days, constant across all views).
         * This ensures the baseline shown in charts matches the dashboard baseline.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val baselineHrvFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    hrvDao
                        .observeSleepHrvSince(TimeRange.THIRTY_DAYS.fromMs(date))
                        .map { records ->
                            if (records.isEmpty()) {
                                null
                            } else {
                                val values =
                                    records
                                        .map { it.rmssdMs }
                                        .sorted()
                                val mid = values.size / 2
                                if (values.size % 2 == 0) {
                                    (values[mid - 1] + values[mid]) / 2f
                                } else {
                                    values[mid]
                                }
                            }
                        }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        /**
         * Baseline RHR (calculated from past 30 days, constant across all views).
         * This ensures the baseline shown in charts matches the dashboard baseline.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val baselineRhrFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    heartRateDao
                        .observeSleepHrSince(TimeRange.THIRTY_DAYS.fromMs(date))
                        .map { records ->
                            if (records.isEmpty()) {
                                null
                            } else {
                                val sessionAvgs =
                                    records
                                        .groupBy { it.sessionId }
                                        .values
                                        .map { sess ->
                                            sess
                                                .map { it.beatsPerMinute }
                                                .average()
                                                .toFloat()
                                        }
                                val sorted = sessionAvgs.sorted()
                                val mid = sorted.size / 2
                                val median =
                                    if (sorted.size % 2 == 0) {
                                        (sorted[mid - 1] + sorted[mid]) / 2f
                                    } else {
                                        sorted[mid]
                                    }
                                median.toInt()
                            }
                        }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
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
                            dailySummaryDao
                                .observeSince(todayMs)
                                .map { it.firstOrNull()?.let { DailySummaryMapper.toDomain(it) } }
                        } else {
                            flow {
                                emit(
                                    dailySummaryDao
                                        .getByDate(
                                            selectedMidnightMs,
                                        )?.let { DailySummaryMapper.toDomain(it) },
                                )
                            }
                        }

                    val dataFlow =
                        combine(
                            summaryFlow,
                            sleepSessionDao.observeFirstSessionEndingInRange(
                                selectedMidnightMs,
                                nextDayMidnightMs,
                            ),
                            dailySummaryDao.observeSince(fromMs).map { list ->
                                list.map { DailySummaryMapper.toDomain(it) }
                            },
                            settingsRepo.userPreferences,
                        ) { latestSummary, latestSession, summaries, prefs ->
                            SleepData(latestSummary, latestSession, summaries, prefs)
                        }

                    combine(
                        dataFlow,
                        baselineHrvFlow,
                        baselineRhrFlow,
                    ) { data, bHrv, bRhr ->
                        val (latestSummary, latestSession, summaries, prefs) = data

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

                        SleepUiState(
                            latestSummary = latestSummary,
                            latestSession = latestSession,
                            dailyHrv = hrvPoints,
                            dailyRhr = rhrPoints,
                            hrvBaseline = bHrv ?: calculateMedian(hrvPoints),
                            rhrBaseline = bRhr?.toFloat() ?: calculateMedian(rhrPoints),
                            goalSleepMinutes = (prefs.goalSleepHours * 60).toInt(),
                            selectedRange = range,
                            selectedDate = date,
                            rangeStartMs = startDayMs,
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
            selectedDateRepository.selectPreviousDay()
        }

        fun onNextDay() {
            selectedDateRepository.selectNextDay()
        }
    }
