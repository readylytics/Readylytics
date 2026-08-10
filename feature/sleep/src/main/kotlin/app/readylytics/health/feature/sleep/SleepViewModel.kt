package app.readylytics.health.feature.sleep

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.aggregateByRange
import app.readylytics.health.core.ui.common.padBucketsToRange
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.di.DefaultDispatcher
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.repository.SleepStageData
import app.readylytics.health.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.domain.scoring.sleep.SleepDaySegment
import app.readylytics.health.domain.scoring.sleep.SleepTrendDay
import app.readylytics.health.domain.scoring.sleep.SleepTrendDayAssembler
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
import kotlin.math.roundToInt

@Immutable
data class SleepUiState(
    val latestSummary: DailySummary? = null,
    val latestMetrics: DailyMetrics? = null,
    val latestSession: SleepSessionData? = null,
    val stageTimeline: List<SleepStageData> = emptyList(),
    val sleepHrSamples: List<HeartRateRecordData> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val selectedTrendRange: TimeRange = TimeRange.SEVEN_DAYS,
    val trendStartOffsetPoints: List<DailyDataPoint> = emptyList(),
    val trendDurationSpanPoints: List<DailyDataPoint> = emptyList(),
    val trendActualDurationPoints: List<DailyDataPoint> = emptyList(),
    val trendDays: List<SleepTrendDay> = emptyList(),
    val trendRangeStartMs: Long = 0,
    val trendScoringZoneId: ZoneId = ZoneId.systemDefault(),
    val trendStartOffsetSummary: PeriodAverageSummary? = null,
    val trendDurationSpanSummary: PeriodAverageSummary? = null,
    val trendActualDurationSummary: PeriodAverageSummary? = null,
    val goalSleepHours: Float = SettingsDefaults.GOAL_SLEEP_HOURS,
    val sleepTimeGaugeData: SleepTimeGaugeData =
        buildSleepTimeGaugeData(
            session = null,
            summary = null,
            goalSleepHours = SettingsDefaults.GOAL_SLEEP_HOURS,
        ),
    val yesterdaySleepScoreRounded: Int? = null,
)

private data class SleepTrendData(
    val startOffsetPoints: List<DailyDataPoint>,
    val durationSpanPoints: List<DailyDataPoint>,
    val actualDurationPoints: List<DailyDataPoint>,
    val trendDays: List<SleepTrendDay>,
    val startOffsetSummary: PeriodAverageSummary? = null,
    val durationSpanSummary: PeriodAverageSummary? = null,
    val actualDurationSummary: PeriodAverageSummary? = null,
)

// Only these preference fields are consumed by the inner pipeline; projecting them through
// distinctUntilChanged means unrelated pref changes (theme, retention, HR zones, ...) do not
// cancel and restart the observe* flows that feed the Sleep screen.
private data class SleepScoringPrefs(
    val scoringZoneId: ZoneId,
    val coreMergeGapMinutes: Int,
    val supplementalCutoffMinutesOfDay: Int,
    val minimumCountedSleepSegmentMinutes: Int,
    val supplementalArchitectureCoveragePercent: Int,
    val goalSleepHours: Float,
)

private fun UserPreferences.toSleepScoringPrefs() =
    SleepScoringPrefs(
        scoringZoneId = scoringZone(),
        coreMergeGapMinutes = coreMergeGapMinutes,
        supplementalCutoffMinutesOfDay = supplementalCutoffMinutesOfDay,
        minimumCountedSleepSegmentMinutes = minimumCountedSleepSegmentMinutes,
        supplementalArchitectureCoveragePercent = supplementalArchitectureCoveragePercent,
        goalSleepHours = goalSleepHours,
    )

private fun SleepSessionData.toSleepDaySegment(): SleepDaySegment {
    val normalizedDurationMinutes =
        if (durationMinutes > 0) {
            durationMinutes
        } else {
            ((endTime - startTime) / 60_000L).toInt()
        }
    return SleepDaySegment(
        stableId = id,
        startTimeMs = startTime,
        endTimeMs = endTime,
        durationMinutes = normalizedDurationMinutes,
        lightSleepMinutes = lightSleepMinutes,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        awakeMinutes = awakeMinutes,
        efficiency = efficiency,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
        sourcePackageName = deviceName,
    )
}

@HiltViewModel
class SleepViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val dailyMetricsRepository: DailyMetricsRepository,
        private val sleepSessionRepository: SleepSessionRepository,
        private val heartRateRepository: HeartRateRepository,
        private val settingsRepo: UserPreferencesReader,
        private val selectedDateRepository: SelectedDateStore,
        private val circadianRepo: CircadianConsistencyRepository,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val savedStateHandle: SavedStateHandle,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val selectedTrendRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

        private val sleepScoringPrefsFlow =
            settingsRepo.userPreferences
                .map { it.toSleepScoringPrefs() }
                .distinctUntilChanged()

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
                sleepScoringPrefsFlow,
            ) { date, range, prefs -> Triple(date, range, prefs) }
                .flatMapLatest { (date, range, prefs) ->
                    val deviceZoneId = ZoneId.systemDefault()
                    val scoringZoneId = prefs.scoringZoneId
                    val selectedMidnightMs =
                        date
                            .atStartOfDay(deviceZoneId)
                            .toInstant()
                            .toEpochMilli()
                    val nextDayMidnightMs =
                        date
                            .plusDays(1)
                            .atStartOfDay(deviceZoneId)
                            .toInstant()
                            .toEpochMilli()

                    val rangeStart = date.minusDays((range.days - 1).toLong())
                    val visibleRangeStartMs = rangeStart.atStartOfDay(scoringZoneId).toInstant().toEpochMilli()
                    val queryStartMs =
                        rangeStart
                            .minusDays(2)
                            .atStartOfDay(scoringZoneId)
                            .toInstant()
                            .toEpochMilli()

                    val summaryFlow =
                        if (date == LocalDate.now(deviceZoneId)) {
                            val todayMs =
                                LocalDate
                                    .now(deviceZoneId)
                                    .atStartOfDay(deviceZoneId)
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
                            .atStartOfDay(deviceZoneId)
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

                    val hrSamplesFlow =
                        sessionFlow.flatMapLatest { session ->
                            if (session == null) {
                                flowOf(emptyList())
                            } else {
                                heartRateRepository.observeSleepHrTimelineForSession(session.id)
                            }
                        }

                    val metricsFlow = dailyMetricsRepository.observeByDate(date)

                    val trendSessionsFlow =
                        sleepSessionRepository.observeSince(queryStartMs).map { list ->
                            val policy =
                                SleepDayPolicy(
                                    coreMergeGapMinutes = prefs.coreMergeGapMinutes,
                                    supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
                                    minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
                                    supplementalArchitectureCoveragePercent =
                                        prefs.supplementalArchitectureCoveragePercent,
                                    scoringZoneId = scoringZoneId,
                                )
                            val trendDays =
                                SleepTrendDayAssembler.assemble(
                                    segments = list.map(SleepSessionData::toSleepDaySegment),
                                    rangeStart = rangeStart,
                                    rangeDays = range.days,
                                    policy = policy,
                                )

                            val startOffsetPoints = mutableListOf<DailyDataPoint>()
                            val durationSpanPoints = mutableListOf<DailyDataPoint>()
                            val actualDurationPoints = mutableListOf<DailyDataPoint>()

                            trendDays.forEachIndexed { dayOffset, trendDay ->
                                val coreStartTimeMs = trendDay.coreStartTimeMs
                                val coreEndTimeMs = trendDay.coreEndTimeMs

                                if (coreStartTimeMs != null && coreEndTimeMs != null) {
                                    val baselineMs =
                                        trendDay.scoreDay
                                            .minusDays(
                                                1,
                                            ).atTime(12, 0)
                                            .atZone(scoringZoneId)
                                            .toInstant()
                                            .toEpochMilli()
                                    val startOffset = (coreStartTimeMs - baselineMs) / 3_600_000f
                                    val endOffset = (coreEndTimeMs - baselineMs) / 3_600_000f
                                    val span = endOffset - startOffset
                                    val actualDuration = trendDay.totalDurationMinutes!! / 60f

                                    startOffsetPoints.add(DailyDataPoint(dayOffset, startOffset))
                                    durationSpanPoints.add(DailyDataPoint(dayOffset, span))
                                    actualDurationPoints.add(DailyDataPoint(dayOffset, actualDuration))
                                } else {
                                    startOffsetPoints.add(DailyDataPoint(dayOffset, null))
                                    durationSpanPoints.add(DailyDataPoint(dayOffset, null))
                                    actualDurationPoints.add(DailyDataPoint(dayOffset, null))
                                }
                            }
                            val trendEndDate = rangeStart.plusDays(range.days.toLong() - 1)
                            val (bucketedStart, startSummary) =
                                startOffsetPoints.aggregateByRange(
                                    range.granularity,
                                    rangeStart,
                                    trendEndDate,
                                    range.days,
                                    valueDecimalPlaces = 1,
                                )
                            val (bucketedSpan, spanSummary) =
                                durationSpanPoints.aggregateByRange(
                                    range.granularity,
                                    rangeStart,
                                    trendEndDate,
                                    range.days,
                                    valueDecimalPlaces = 1,
                                )
                            val (bucketedDuration, durationSummary) =
                                actualDurationPoints.aggregateByRange(
                                    range.granularity,
                                    rangeStart,
                                    trendEndDate,
                                    range.days,
                                    valueDecimalPlaces = 1,
                                )

                            val paddedStart = bucketedStart.padBucketsToRange(range.granularity, rangeStart, trendEndDate)
                            val paddedSpan = bucketedSpan.padBucketsToRange(range.granularity, rangeStart, trendEndDate)
                            val paddedDuration = bucketedDuration.padBucketsToRange(range.granularity, rangeStart, trendEndDate)

                            SleepTrendData(
                                startOffsetPoints = paddedStart,
                                durationSpanPoints = paddedSpan,
                                actualDurationPoints = paddedDuration,
                                trendDays = trendDays,
                                startOffsetSummary = startSummary,
                                durationSpanSummary = spanSummary,
                                actualDurationSummary = durationSummary,
                            )
                        }

                    combine(
                        summaryFlow,
                        sessionFlow,
                        stagesFlow,
                        metricsFlow,
                        trendSessionsFlow,
                        yesterdaySummaryFlow,
                        hrSamplesFlow,
                    ) { array ->
                        val latestSummary = array[0] as DailySummary?
                        val latestSession = array[1] as SleepSessionData?

                        @Suppress("UNCHECKED_CAST")
                        val stages = array[2] as List<SleepStageData>
                        val latestMetrics = array[3] as DailyMetrics?

                        @Suppress("UNCHECKED_CAST")
                        val trendData = array[4] as SleepTrendData
                        val yesterdaySummary = array[5] as DailySummary?

                        @Suppress("UNCHECKED_CAST")
                        val hrSamples = array[6] as List<HeartRateRecordData>

                        SleepUiState(
                            latestSummary = latestSummary,
                            latestMetrics = latestMetrics,
                            latestSession = latestSession,
                            stageTimeline = stages,
                            selectedDate = date,
                            selectedTrendRange = range,
                            trendStartOffsetPoints = trendData.startOffsetPoints,
                            trendDurationSpanPoints = trendData.durationSpanPoints,
                            trendActualDurationPoints = trendData.actualDurationPoints,
                            trendDays = trendData.trendDays,
                            trendRangeStartMs = visibleRangeStartMs,
                            trendScoringZoneId = scoringZoneId,
                            trendStartOffsetSummary = trendData.startOffsetSummary,
                            trendDurationSpanSummary = trendData.durationSpanSummary,
                            trendActualDurationSummary = trendData.actualDurationSummary,
                            goalSleepHours = prefs.goalSleepHours,
                            sleepTimeGaugeData =
                                buildSleepTimeGaugeData(
                                    session = latestSession,
                                    summary = latestSummary,
                                    goalSleepHours = prefs.goalSleepHours,
                                ),
                            yesterdaySleepScoreRounded = yesterdaySummary?.sleepScore?.roundToInt(),
                            sleepHrSamples = hrSamples,
                        )
                    }.distinctUntilChanged()
                        // isSyncing is merged in after the heavy pipeline instead of inside it
                        // (mirrors DashboardViewModel.kt:104-113) so a sync toggle only triggers a
                        // cheap copy, not a full re-run of the trend-day-loop unpacking above.
                        // isLoading means "true first-load, no data yet" (skeleton); isRefreshing
                        // tracks every sync regardless of data presence. The "no data yet" signal
                        // is based on whether the trend chart has any real historical point loaded,
                        // not on whether the *selected day's* summary/session exists --
                        // latestSummary/latestSession are scoped to the selected date, so on the
                        // first sync of a new day (before today's session/summary is computed) they
                        // are null even though the trend already has unchanged history loaded.
                        // Checking the trend list instead avoids flashing the skeleton and tearing
                        // down/rebuilding the Vico chart once per day. The trend point lists are
                        // always padded to range.days entries (null-valued, never actually empty),
                        // so this must be an any-non-null check, not a trend-list emptiness check.
                        .combine(
                            foregroundSyncController.isSyncing,
                        ) { state, syncing ->
                            val hasHistoricalData = state.trendStartOffsetPoints.any { it.value != null }
                            state.copy(
                                isLoading = syncing && !hasHistoricalData,
                                isRefreshing = syncing,
                            )
                        }
                }.flowOn(defaultDispatcher)
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
