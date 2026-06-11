package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailyMetrics
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.repository.DailyMetricsRepository
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
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
    val selectedTrendRange: TimeRange = TimeRange.SEVEN_DAYS,
    val trendStartOffsetPoints: List<DailyDataPoint> = emptyList(),
    val trendDurationSpanPoints: List<DailyDataPoint> = emptyList(),
    val trendActualDurationPoints: List<DailyDataPoint> = emptyList(),
    val trendRangeStartMs: Long = 0,
)

@HiltViewModel
class SleepViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val dailyMetricsRepository: DailyMetricsRepository,
        private val sleepSessionRepository: SleepSessionRepository,
        private val heartRateRepository: HeartRateRepository,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val circadianRepo: CircadianConsistencyRepository,
        private val foregroundSyncController: ForegroundSyncController,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val selectedTrendRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

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
                selectedDateRepository.selectedDate,
                selectedTrendRangeFlow,
            ) { date, range -> date to range }
                .flatMapLatest { (date, range) ->
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

                    val rangeStart = date.minusDays((range.days - 1).toLong())
                    val visibleRangeStartMs = rangeStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val queryStartMs =
                        rangeStart
                            .minusDays(2)
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

                    val metricsFlow = dailyMetricsRepository.observeByDate(date)

                    val trendSessionsFlow =
                        sleepSessionRepository.observeSince(queryStartMs).map { list ->
                            val filtered = list.filter { it.endTime <= nextDayMidnightMs }
                            val sessionsByDay =
                                filtered.groupBy { session ->
                                    Instant.ofEpochMilli(session.endTime).atZone(zoneId).toLocalDate()
                                }

                            val startOffsetPoints = mutableListOf<DailyDataPoint>()
                            val durationSpanPoints = mutableListOf<DailyDataPoint>()
                            val actualDurationPoints = mutableListOf<DailyDataPoint>()

                            for (dayOffset in 0 until range.days) {
                                val targetDate = rangeStart.plusDays(dayOffset.toLong())
                                val sessionsForDay = sessionsByDay[targetDate]
                                val session = sessionsForDay?.firstOrNull()

                                if (session != null) {
                                    val baselineMs =
                                        targetDate
                                            .minusDays(
                                                1,
                                            ).atTime(12, 0)
                                            .atZone(zoneId)
                                            .toInstant()
                                            .toEpochMilli()
                                    val startOffset = (session.startTime - baselineMs) / 3_600_000f
                                    val endOffset = (session.endTime - baselineMs) / 3_600_000f
                                    val span = endOffset - startOffset
                                    val actualDuration = (session.durationMinutes - session.awakeMinutes) / 60f

                                    startOffsetPoints.add(DailyDataPoint(dayOffset, startOffset))
                                    durationSpanPoints.add(DailyDataPoint(dayOffset, span))
                                    actualDurationPoints.add(DailyDataPoint(dayOffset, actualDuration))
                                } else {
                                    startOffsetPoints.add(DailyDataPoint(dayOffset, null))
                                    durationSpanPoints.add(DailyDataPoint(dayOffset, null))
                                    actualDurationPoints.add(DailyDataPoint(dayOffset, null))
                                }
                            }
                            Triple(startOffsetPoints, durationSpanPoints, actualDurationPoints)
                        }

                    combine(
                        summaryFlow,
                        sessionFlow,
                        stagesFlow,
                        foregroundSyncController.isSyncing,
                        metricsFlow,
                        trendSessionsFlow,
                    ) { array ->
                        val latestSummary = array[0] as DailySummary?
                        val latestSession = array[1] as SleepSessionData?

                        @Suppress("UNCHECKED_CAST")
                        val stages = array[2] as List<SleepStageData>
                        val isSyncing = array[3] as Boolean
                        val latestMetrics = array[4] as DailyMetrics?

                        @Suppress("UNCHECKED_CAST")
                        val trendData =
                            array[5] as Triple<
                                List<DailyDataPoint>,
                                List<DailyDataPoint>,
                                List<DailyDataPoint>,
                            >

                        SleepUiState(
                            latestSummary = latestSummary,
                            latestMetrics = latestMetrics,
                            latestSession = latestSession,
                            stageTimeline = stages,
                            selectedDate = date,
                            isLoading = isSyncing,
                            selectedTrendRange = range,
                            trendStartOffsetPoints = trendData.first,
                            trendDurationSpanPoints = trendData.second,
                            trendActualDurationPoints = trendData.third,
                            trendRangeStartMs = visibleRangeStartMs,
                        )
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SleepUiState(isLoading = true),
                )

        val earliestDate: StateFlow<LocalDate?> =
            selectedDateRepository.earliestDate
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        fun onDateSelected(date: LocalDate) {
            viewModelScope.launch {
                selectedDateRepository.updateSelectedDate(date)
            }
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

        fun onTrendRangeSelected(range: TimeRange) {
            selectedTrendRangeFlow.value = range
        }
    }
