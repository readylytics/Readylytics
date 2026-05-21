package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutData
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ComputeWorkoutTrimpUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class WorkoutDisplayItem(
    val workout: WorkoutData,
    val computedTrimp: Float,
)

data class WorkoutsUiState(
    val latestSummary: DailySummary? = null,
    val dailyTrimp: List<DailyDataPoint> = emptyList(),
    val dailyStrainRatio: List<DailyDataPoint> = emptyList(),
    val recentWorkouts: List<WorkoutDisplayItem> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val paiDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val todayPaiScore: Float? = null,
    val isLoading: Boolean = false,
)

private data class WorkoutFlowData(
    val latestSummary: DailySummary?,
    val allWorkouts: List<WorkoutData>,
    val trimpSummaries: List<DailySummary>,
    val paiSummaries: List<DailySummary>,
    val prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences,
)

@HiltViewModel
class WorkoutsViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val workoutRepository: WorkoutRepository,
        private val heartRateRepository: HeartRateRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val scoringCalculator: ScoringCalculator,
        private val settingsRepo: SettingsRepository,
        private val computeWorkoutTrimpUseCase:
            com.gregor.lauritz.healthdashboard.domain.scoring.ComputeWorkoutTrimpUseCase,
        private val foregroundSyncController: ForegroundSyncController,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )

        val selectedRange = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
                foregroundSyncController.isSyncing,
            ) { range, date, isSyncing -> Triple(range, date, isSyncing) }
                .flatMapLatest { (range, date, isSyncing) ->
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
                            }.flowOn(Dispatchers.IO)
                        }

                    val paiFromMs =
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
                            dailySummaryRepository.observeSince(paiFromMs),
                            settingsRepo.userPreferences,
                        ) { latest, allWorkouts, trimpSummaries, paiSummaries, prefs ->
                            WorkoutFlowData(latest, allWorkouts, trimpSummaries, paiSummaries, prefs)
                        }

                    dataFlow.flatMapLatest { data ->
                        flow {
                            val (latest, allWorkouts, trimpSummaries, paiSummaries, prefs) = data

                            val filteredWorkouts =
                                allWorkouts.filter {
                                    it.startTime <
                                        selectedMidnightMs + TimeUnit.DAYS.toMillis(1)
                                }
                            val trimpByDay: Map<Long, Float> =
                                trimpSummaries
                                    .associate { summary ->
                                        val dayMs =
                                            summary.date
                                                .atStartOfDay(zoneId)
                                                .toInstant()
                                                .toEpochMilli()
                                        dayMs to (summary.totalTrimp ?: 0f)
                                    }

                            val trimpByDate: Map<LocalDate, Float> =
                                trimpSummaries
                                    .associate { summary ->
                                        summary.date to (summary.totalTrimp ?: 0f)
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
                                        val ctl =
                                            scoringCalculator.computeCtlEmaWithDecay(
                                                trimpByDate,
                                                currentDayDate,
                                            )
                                        val atl =
                                            scoringCalculator.computeAtlEmaWithDecay(
                                                trimpByDate,
                                                currentDayDate,
                                            )
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
                                        com.gregor.lauritz.healthdashboard.domain.scoring.ComputeWorkoutTrimpUseCase
                                            .HeartRateSample(
                                                timestamp = java.time.Instant.ofEpochMilli(it.timestampMs),
                                                bpm = it.beatsPerMinute,
                                            )
                                    }
                            }

                            val recentItems =
                                recentWorkouts
                                    .map { workout ->
                                        val workoutDate =
                                            Instant
                                                .ofEpochMilli(
                                                    workout.startTime,
                                                ).atZone(zoneId)
                                                .toLocalDate()
                                        val rhrBaseline = summaryByDate[workoutDate]?.restingHrBaseline?.toFloat()
                                        val samples = samplesByWorkoutId[workout.id] ?: emptyList()

                                        val computedTrimp =
                                            computeWorkoutTrimpUseCase.execute(
                                                workoutStartTime = workout.startTime,
                                                workoutEndTime = workout.endTime,
                                                workoutAvgHr = workout.avgHr,
                                                samples = samples,
                                                prefs = prefs,
                                                restingHrBaseline = rhrBaseline,
                                                storedTrimp = workout.trimp,
                                            )
                                        WorkoutDisplayItem(workout, computedTrimp)
                                    }

                            WorkoutsUiState(
                                latestSummary = latest,
                                dailyTrimp = dailyTrimp,
                                dailyStrainRatio = dailyStrainRatio,
                                recentWorkouts = recentItems,
                                selectedRange = range,
                                selectedDate = date,
                                rangeStartMs = displayStartDayMs,
                                paiDailyBreakdown = buildPaiBreakdown(date, paiSummaries),
                                todayPaiScore = latest?.paiScore,
                                isLoading = isSyncing,
                            ).also { emit(it) }
                        }
                    }
                }.flowOn(Dispatchers.Default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = WorkoutsUiState(),
                )

        private fun buildPaiBreakdown(
            endDate: LocalDate,
            summaries: List<DailySummary>,
        ): List<Pair<String, Float>> {
            val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            return (6 downTo 0).map { daysBack ->
                val day = endDate.minusDays(daysBack.toLong())
                val entry = summaries.firstOrNull { it.date == day }
                day.format(fmt) to (entry?.paiScore ?: 0f)
            }
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
