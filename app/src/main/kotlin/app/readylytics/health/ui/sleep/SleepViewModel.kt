package app.readylytics.health.ui.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.repository.SleepStageData
import app.readylytics.health.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sync.ForegroundSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.math.roundToInt

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
    val goalSleepHours: Float = SettingsDefaults.GOAL_SLEEP_HOURS,
    val sleepTimeGaugeData: SleepTimeGaugeData =
        buildSleepTimeGaugeData(
            session = null,
            summary = null,
            goalSleepHours = SettingsDefaults.GOAL_SLEEP_HOURS,
        ),
    val yesterdaySleepScoreRounded: Int? = null,
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
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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

                    val yesterdayMidnightMs =
                        date
                            .minusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    val yesterdaySummaryFlow =
                        dailySummaryRepository.observeByDate(yesterdayMidnightMs).flowOn(ioDispatcher)

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
                        settingsRepo.userPreferences,
                        yesterdaySummaryFlow,
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
                        val prefs = array[6] as UserPreferences
                        val yesterdaySummary = array[7] as DailySummary?

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
                            goalSleepHours = prefs.goalSleepHours,
                            sleepTimeGaugeData =
                                buildSleepTimeGaugeData(
                                    session = latestSession,
                                    summary = latestSummary,
                                    goalSleepHours = prefs.goalSleepHours,
                                ),
                            yesterdaySleepScoreRounded = yesterdaySummary?.sleepScore?.roundToInt(),
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
