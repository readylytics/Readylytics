package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val ACUTE_DAYS = 7
private const val CHRONIC_DAYS = 42

data class WorkoutsUiState(
    val latestSummary: DailySummary? = null,
    val dailyTrimp: List<DailyDataPoint> = emptyList(),
    val dailyStrainRatio: List<DailyDataPoint> = emptyList(),
    val recentWorkouts: List<WorkoutRecordEntity> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val paiDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val todayPaiScore: Float? = null,
)

@HiltViewModel
class WorkoutsViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val workoutDao: WorkoutDao,
        private val selectedDateRepository: SelectedDateRepository,
        private val scoringCalculator: ScoringCalculator,
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
                            Instant.ofEpochMilli(earliestWorkoutMs).atZone(zoneId).toLocalDate()
                        } else {
                            null
                        }

                    val displayFromMs = range.fromMs(date)
                    val displayStartDayMs = displayFromMs.truncateToDayMs()
                    // Fetch extra history so chronic (42-day) window is valid from day 1 of the range.
                    val fetchFromMs = displayStartDayMs - TimeUnit.DAYS.toMillis(CHRONIC_DAYS.toLong())

                    val selectedMidnightMs =
                        date
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    val summaryFlow =
                        if (date == LocalDate.now(zoneId)) {
                            dailySummaryDao.observeLatest().map { it?.let { DailySummaryMapper.toDomain(it) } }
                        } else {
                            flow { emit(dailySummaryDao.getByDate(selectedMidnightMs)?.let { DailySummaryMapper.toDomain(it) }) }
                        }

                    val paiFromMs = date.minusDays(6)
                        .atStartOfDay(zoneId).toInstant().toEpochMilli()
                    combine(
                        summaryFlow,
                        workoutDao.observeSince(fetchFromMs),
                        dailySummaryDao.observeSince(paiFromMs).map { list -> list.map { DailySummaryMapper.toDomain(it) } },
                    ) { latest, allWorkouts, paiSummaries ->
                        val filteredWorkouts = allWorkouts.filter { it.startTime < selectedMidnightMs + TimeUnit.DAYS.toMillis(1) }
                        val trimpByDay: Map<Long, Float> =
                            filteredWorkouts
                                .groupBy { it.startTime.truncateToDayMs() }
                                .mapValues { (_, ws) -> ws.sumOf { it.trimp.toDouble() }.toFloat() }

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
                            if (trimp != null && trimp > 0f) {
                                dailyTrimp.add(DailyDataPoint(dayOffset = i, value = trimp))
                            }

                            val currentDayDate = Instant.ofEpochMilli(dayMidnight).atZone(zoneId).toLocalDate()
                            val acuteFrom = currentDayDate.minusDays((ACUTE_DAYS - 1).toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli()
                            val chronicFrom = currentDayDate.minusDays((CHRONIC_DAYS - 1).toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli()

                            val acuteSum = trimpByDay.filterKeys { it in acuteFrom..dayMidnight }.values.sum()
                            val chronicTrimpList = trimpByDay.filterKeys { it in chronicFrom..dayMidnight }.values.toList()

                            val dataTenureDays =
                                if (earliestLocalDate != null) {
                                    ChronoUnit.DAYS.between(earliestLocalDate, currentDayDate).toInt() + 1
                                } else {
                                    0
                                }

                            if (dataTenureDays >= 7 && chronicTrimpList.isNotEmpty()) {
                                val ctl = scoringCalculator.computeCtlEma(chronicTrimpList)
                                val atl = acuteSum / ACUTE_DAYS.toFloat()
                                val sr = scoringCalculator.computeStrainRatio(atl, ctl)
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
                            paiDailyBreakdown = buildPaiBreakdown(date, paiSummaries),
                            todayPaiScore = latest?.paiScore,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
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
        }

        fun onPreviousDay() {
            selectedDateRepository.selectPreviousDay()
        }

        fun onNextDay() {
            selectedDateRepository.selectNextDay()
        }
    }
