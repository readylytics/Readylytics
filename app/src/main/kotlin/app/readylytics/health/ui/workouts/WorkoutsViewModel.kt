package app.readylytics.health.ui.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import app.readylytics.health.domain.util.truncateToDayMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class WorkoutDisplayItem(
    val workout: WorkoutData,
    val gainedStrain: Float,
    val computedTrimp: Int,
    val gainedStrainDisplay: String,
)

data class WorkoutsUiState(
    val latestSummary: DailySummary? = null,
    val latestMetrics: DailyMetrics? = null,
    val dailyTrimp: List<DailyDataPoint> = emptyList(),
    val dailyStrainRatio: List<DailyDataPoint> = emptyList(),
    val recentWorkouts: List<WorkoutDisplayItem> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val rasDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val todayRasScore: Float? = null,
    val isLoading: Boolean = false,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val yesterdayStrainRatio: Float? = null,
    val yesterdayReadiness: Float? = null,
    val todayStrainIncrease: Float? = null,
)

private data class WorkoutFlowData(
    val latestSummary: DailySummary?,
    val allWorkouts: List<WorkoutData>,
    val trimpSummaries: List<DailySummary>,
    val rasSummaries: List<DailySummary>,
    val prefs: app.readylytics.health.data.preferences.UserPreferences,
)

private data class CombinedParams(
    val range: TimeRange,
    val date: LocalDate,
    val isSyncing: Boolean,
    val page: Int,
)

@HiltViewModel
class WorkoutsViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val workoutRepository: WorkoutRepository,
        private val heartRateRepository: HeartRateRepository,
        private val selectedDateRepository: SelectedDateStore,
        private val scoringCalculator: ScoringCalculator,
        private val settingsRepo: UserPreferencesReader,
        private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val savedStateHandle: SavedStateHandle,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )

        val selectedRange = _selectedRange.asStateFlow()

        private val _currentPage = MutableStateFlow(1)
        val currentPage = _currentPage.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
                foregroundSyncController.isSyncing,
                _currentPage,
            ) { range, date, isSyncing, page -> CombinedParams(range, date, isSyncing, page) }
                .scan(null as CombinedParams?) { prev, current ->
                    if (prev != null && (prev.range != current.range || prev.date != current.date)) {
                        _currentPage.value = 1
                        current.copy(page = 1)
                    } else {
                        current
                    }
                }.filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { params ->
                    val range = params.range
                    val date = params.date
                    val isSyncing = params.isSyncing
                    val page = params.page
                    val earliestWorkoutMs = workoutRepository.getEarliestWorkoutTimestamp() ?: 0L
                    val zoneId = ZoneId.systemDefault()
                    val earliestLocalDate =
                        if (earliestWorkoutMs > 0) {
                            Instant.ofEpochMilli(earliestWorkoutMs).atZone(zoneId).toLocalDate()
                        } else {
                            null
                        }

                    val displayFromMs = range.fromMs(date)
                    val displayStartDayMs = displayFromMs.truncateToDayMs()
                    // Fetch extra history so chronic (42-day) window is valid from day 1 of the range.
                    val fetchFromMs = displayStartDayMs - TimeUnit.DAYS.toMillis(ScoringConstants.CHRONIC_DAYS)

                    val selectedMidnightMs =
                        date
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    val summaryFlow =
                        if (date == LocalDate.now(zoneId)) {
                            dailySummaryRepository.observeLatest()
                        } else {
                            flow {
                                emit(dailySummaryRepository.getByDate(selectedMidnightMs))
                            }.flowOn(ioDispatcher)
                        }

                    val rasFromMs =
                        date
                            .minusDays(6)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    val dataFlow =
                        combine(
                            summaryFlow,
                            workoutRepository.observeSince(fetchFromMs),
                            dailySummaryRepository.observeSince(fetchFromMs),
                            dailySummaryRepository.observeSince(rasFromMs),
                            settingsRepo.userPreferences,
                        ) { latest, allWorkouts, trimpSummaries, rasSummaries, prefs ->
                            WorkoutFlowData(latest, allWorkouts, trimpSummaries, rasSummaries, prefs)
                        }

                    dataFlow.flatMapLatest { data ->
                        flow {
                            val (latest, allWorkouts, trimpSummaries, rasSummaries, prefs) = data

                            val filteredWorkouts =
                                allWorkouts.filter {
                                    it.startTime <
                                        selectedMidnightMs + TimeUnit.DAYS.toMillis(1)
                                }
                            val trimpByDate: Map<LocalDate, Float> =
                                trimpSummaries.associate { summary ->
                                    summary.date to
                                        (LoadSourceSelector.selectTrimp(summary, prefs.strainLoadSourceMode) ?: 0f)
                                }

                            val trimpByDay: Map<Long, Float> =
                                trimpSummaries.associate { summary ->
                                    val dayMs =
                                        summary.date
                                            .atStartOfDay(zoneId)
                                            .toInstant()
                                            .toEpochMilli()
                                    dayMs to (trimpByDate[summary.date] ?: 0f)
                                }

                            val displayDayMidnights =
                                buildList<Long> {
                                    var current = Instant.ofEpochMilli(displayStartDayMs).atZone(zoneId).toLocalDate()
                                    val end = date
                                    while (!current.isAfter(end)) {
                                        add(current.atStartOfDay(zoneId).toInstant().toEpochMilli())
                                        current = current.plusDays(1)
                                    }
                                }

                            val displayStartDayDate =
                                Instant
                                    .ofEpochMilli(
                                        displayStartDayMs,
                                    ).atZone(zoneId)
                                    .toLocalDate()
                            val ctlSeries =
                                scoringCalculator.computeCtlEmaSeries(
                                    trimpByDate,
                                    displayStartDayDate,
                                    date,
                                )
                            val atlSeries =
                                scoringCalculator.computeAtlEmaSeries(
                                    trimpByDate,
                                    displayStartDayDate,
                                    date,
                                )

                            val dailyTrimp = mutableListOf<DailyDataPoint>()
                            val dailyStrainRatio = mutableListOf<DailyDataPoint>()

                            displayDayMidnights.forEachIndexed { i, dayMidnight ->
                                val trimp = trimpByDay[dayMidnight]
                                dailyTrimp.add(
                                    DailyDataPoint(
                                        dayOffset = i,
                                        value =
                                            if (trimp != null &&
                                                trimp > 0f
                                            ) {
                                                trimp
                                            } else {
                                                null
                                            },
                                    ),
                                )

                                val currentDayDate = Instant.ofEpochMilli(dayMidnight).atZone(zoneId).toLocalDate()

                                val dataTenureDays =
                                    if (earliestLocalDate != null) {
                                        ChronoUnit.DAYS.between(earliestLocalDate, currentDayDate).toInt() + 1
                                    } else {
                                        0
                                    }

                                val sr =
                                    if (dataTenureDays >= 7) {
                                        val ctl = ctlSeries[currentDayDate] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                                        val atl = atlSeries[currentDayDate] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                                        scoringCalculator.computeStrainRatio(atl, ctl)
                                    } else {
                                        null
                                    }
                                dailyStrainRatio.add(DailyDataPoint(dayOffset = i, value = sr))
                            }

                            val summaryByDate = trimpSummaries.associateBy { it.date }

                            val recentWorkouts = filteredWorkouts.filter { it.startTime >= displayFromMs }

                            // Batch load HR samples for all recent workouts
                            val samplesByWorkoutId =
                                mutableMapOf<
                                    String,
                                    List<ComputeWorkoutTrimpUseCase.HeartRateSample>,
                                >()
                            for (workout in recentWorkouts) {
                                val samples = heartRateRepository.getByTimeRange(workout.startTime, workout.endTime)
                                samplesByWorkoutId[workout.id] =
                                    samples.map {
                                        app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
                                            .HeartRateSample(
                                                timestamp = java.time.Instant.ofEpochMilli(it.timestampMs),
                                                bpm = it.beatsPerMinute,
                                            )
                                    }
                            }

                            val recentItems =
                                recentWorkouts
                                    .map { workout ->
                                        val samples = samplesByWorkoutId[workout.id] ?: emptyList()

                                        val displayMetrics =
                                            getWorkoutDisplayMetricsUseCase.execute(
                                                workout = workout,
                                                samples = samples,
                                            )

                                        WorkoutDisplayItem(
                                            workout = workout,
                                            gainedStrain = displayMetrics.gainedStrain,
                                            computedTrimp = displayMetrics.computedTrimp,
                                            gainedStrainDisplay = displayMetrics.gainedStrainDisplay,
                                        )
                                    }

                            val pageSize = 10
                            val totalItems = recentItems.size
                            val totalPages = maxOf(1, (totalItems + pageSize - 1) / pageSize)
                            val clampedPage = page.coerceIn(1, totalPages)
                            val startIndex = (clampedPage - 1) * pageSize
                            val endIndex = minOf(startIndex + pageSize, totalItems)
                            val paginatedItems =
                                if (startIndex < totalItems) {
                                    recentItems.subList(startIndex, endIndex)
                                } else {
                                    emptyList()
                                }

                            val yesterday = date.minusDays(1)
                            val yesterdaySummary = rasSummaries.firstOrNull { it.date == yesterday }
                            val yesterdayMetrics = yesterdaySummary?.let { DailyMetricsMapper.toMetrics(it, prefs) }

                            val dataTenureDaysForDate =
                                if (earliestLocalDate != null) {
                                    ChronoUnit.DAYS.between(earliestLocalDate, date).toInt() + 1
                                } else {
                                    0
                                }

                            val todayStrainIncrease =
                                if (dataTenureDaysForDate >= 7) {
                                    val trimpByDateWithout = trimpByDate.toMutableMap().apply { put(date, 0f) }
                                    val ctlWith = ctlSeries[date] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                                    val atlWith = atlSeries[date] ?: ScoringConstants.DEFAULT_FITNESS_LEVEL
                                    val srWith = scoringCalculator.computeStrainRatio(atlWith, ctlWith)

                                    val ctlWithout = scoringCalculator.computeCtlEmaWithDecay(trimpByDateWithout, date)
                                    val atlWithout = scoringCalculator.computeAtlEmaWithDecay(trimpByDateWithout, date)
                                    val srWithout = scoringCalculator.computeStrainRatio(atlWithout, ctlWithout)

                                    (srWith - srWithout).coerceAtLeast(0f)
                                } else {
                                    null
                                }

                            WorkoutsUiState(
                                latestSummary = latest,
                                latestMetrics = latest?.let { DailyMetricsMapper.toMetrics(it, prefs) },
                                dailyTrimp = dailyTrimp,
                                dailyStrainRatio = dailyStrainRatio,
                                recentWorkouts = paginatedItems,
                                selectedRange = range,
                                selectedDate = date,
                                rangeStartMs = displayStartDayMs,
                                rasDailyBreakdown = buildRasBreakdown(date, rasSummaries, prefs),
                                todayRasScore =
                                    latest?.let {
                                        LoadSourceSelector.selectDailyRas(
                                            it,
                                            prefs.rasSourceMode,
                                        )
                                    },
                                isLoading = isSyncing,
                                currentPage = clampedPage,
                                totalPages = totalPages,
                                yesterdayStrainRatio = yesterdayMetrics?.strainRatioRaw,
                                yesterdayReadiness = yesterdayMetrics?.readinessRounded?.toFloat(),
                                todayStrainIncrease = todayStrainIncrease,
                            ).also { emit(it) }
                        }
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = WorkoutsUiState(isLoading = true),
                )

        private fun buildRasBreakdown(
            endDate: LocalDate,
            summaries: List<DailySummary>,
            prefs: app.readylytics.health.data.preferences.UserPreferences,
        ): List<Pair<String, Float>> {
            val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            return (6 downTo 0).map { daysBack ->
                val day = endDate.minusDays(daysBack.toLong())
                val entry = summaries.firstOrNull { it.date == day }
                val ras = entry?.let { LoadSourceSelector.selectDailyRas(it, prefs.rasSourceMode) }
                day.format(fmt) to (ras ?: 0f)
            }
        }

        fun onRangeSelected(range: TimeRange) {
            _currentPage.value = 1
            _selectedRange.value = range
            savedStateHandle["selectedRange"] = range
        }

        val earliestDate: StateFlow<LocalDate?> =
            selectedDateRepository.earliestDate
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        fun onDateSelected(date: LocalDate) {
            _currentPage.value = 1
            viewModelScope.launch {
                selectedDateRepository.updateSelectedDate(date)
            }
        }

        fun onPreviousDay() {
            _currentPage.value = 1
            viewModelScope.launch {
                selectedDateRepository.selectPreviousDay()
            }
        }

        fun onNextDay() {
            _currentPage.value = 1
            viewModelScope.launch {
                selectedDateRepository.selectNextDay()
            }
        }

        fun onNextPage() {
            val totalPages = uiState.value.totalPages
            if (_currentPage.value < totalPages) {
                _currentPage.value += 1
            }
        }

        fun onPreviousPage() {
            if (_currentPage.value > 1) {
                _currentPage.value -= 1
            }
        }
    }
