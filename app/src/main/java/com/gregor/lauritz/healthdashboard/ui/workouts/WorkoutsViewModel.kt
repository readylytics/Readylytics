package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.sleep.truncateToDayMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val ACUTE_DAYS = 7
private const val CHRONIC_DAYS = 42

data class WorkoutsUiState(
    val latestSummary: DailySummaryEntity? = null,
    val dailyTrimp: List<DailyDataPoint> = emptyList(),
    val dailyStrainRatio: List<DailyDataPoint> = emptyList(),
    val recentWorkouts: List<WorkoutRecordEntity> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
)

@HiltViewModel
class WorkoutsViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val workoutDao: WorkoutDao,
        private val selectedDateRepository: SelectedDateRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)
        val selectedRange = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
            ) { range, date -> range to date }
                .flatMapLatest { (range, date) ->
                    val earliestWorkoutMs = workoutDao.getEarliestWorkoutTimestamp() ?: 0L
                    val zoneId = ZoneId.systemDefault()
                    val earliestLocalDate =
                        if (earliestWorkoutMs > 0) {
                            java.time.Instant.ofEpochMilli(earliestWorkoutMs).atZone(zoneId).toLocalDate()
                        } else {
                            null
                        }

                    val displayFromMs = range.fromMs(date)
                    val displayStartDayMs = truncateToDayMs(displayFromMs)
                    // Fetch extra history so chronic (42-day) window is valid from day 1 of the range.
                    val fetchFromMs = displayStartDayMs - TimeUnit.DAYS.toMillis(CHRONIC_DAYS.toLong())

                    val selectedMidnightMs =
                        date
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    val summaryFlow =
                        if (date == LocalDate.now(zoneId)) {
                            dailySummaryDao.observeLatest()
                        } else {
                            flow { emit(dailySummaryDao.getByDate(selectedMidnightMs)) }
                        }

                    combine(
                        summaryFlow,
                        workoutDao.observeSince(fetchFromMs),
                    ) { latest, allWorkouts ->
                        val filteredWorkouts = allWorkouts.filter { it.startTime < selectedMidnightMs + TimeUnit.DAYS.toMillis(1) }
                        val trimpByDay: Map<Long, Float> =
                            filteredWorkouts
                                .groupBy { truncateToDayMs(it.startTime) }
                                .mapValues { (_, ws) -> ws.sumOf { it.trimp.toDouble() }.toFloat() }

                        val displayDayMidnights =
                            buildList<Long> {
                                val cal = Calendar.getInstance()
                                cal.timeInMillis = displayStartDayMs
                                while (cal.timeInMillis <= selectedMidnightMs) {
                                    add(cal.timeInMillis)
                                    cal.add(Calendar.DAY_OF_YEAR, 1)
                                }
                            }

                        val dailyTrimp = mutableListOf<DailyDataPoint>()
                        val dailyStrainRatio = mutableListOf<DailyDataPoint>()

                        displayDayMidnights.forEachIndexed { i, dayMidnight ->
                            val trimp = trimpByDay[dayMidnight]
                            if (trimp != null && trimp > 0f) {
                                dailyTrimp.add(DailyDataPoint(dayOffset = i, value = trimp))
                            }

                            val acuteFrom =
                                Calendar.getInstance().run {
                                    timeInMillis = dayMidnight
                                    add(Calendar.DAY_OF_YEAR, -(ACUTE_DAYS - 1))
                                    timeInMillis
                                }
                            val chronicFrom =
                                Calendar.getInstance().run {
                                    timeInMillis = dayMidnight
                                    add(Calendar.DAY_OF_YEAR, -(CHRONIC_DAYS - 1))
                                    timeInMillis
                                }

                            val acuteSum = trimpByDay.filterKeys { it in acuteFrom..dayMidnight }.values.sum()
                            val chronicSum = trimpByDay.filterKeys { it in chronicFrom..dayMidnight }.values.sum()

                            val currentDayDate =
                                java.time.Instant
                                    .ofEpochMilli(dayMidnight)
                                    .atZone(zoneId)
                                    .toLocalDate()

                            val dataTenureDays =
                                if (earliestLocalDate != null) {
                                    ChronoUnit.DAYS.between(earliestLocalDate, currentDayDate).toInt() + 1
                                } else {
                                    0
                                }

                            if (dataTenureDays >= 7 && chronicSum > 0f) {
                                val ctl = chronicSum / minOf(dataTenureDays, CHRONIC_DAYS).toFloat()
                                val atl = acuteSum / ACUTE_DAYS.toFloat()
                                val sr = if (ctl > 0) atl / ctl else 0f
                                dailyStrainRatio.add(DailyDataPoint(dayOffset = i, value = sr))
                            }
                        }

                        WorkoutsUiState(
                            latestSummary = latest,
                            dailyTrimp = dailyTrimp,
                            dailyStrainRatio = dailyStrainRatio,
                            recentWorkouts = filteredWorkouts.filter { it.startTime >= displayFromMs },
                            selectedRange = range,
                            selectedDate = date,
                            rangeStartMs = displayStartDayMs,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = WorkoutsUiState(),
                )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }

        fun onPreviousDay() {
            selectedDateRepository.selectPreviousDay()
        }

        fun onNextDay() {
            selectedDateRepository.selectNextDay()
        }
    }
