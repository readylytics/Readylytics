package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
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
import kotlinx.coroutines.flow.stateIn
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
    val rangeStartMs: Long = System.currentTimeMillis(),
)

@HiltViewModel
class WorkoutsViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val workoutDao: WorkoutDao,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)
        val selectedRange = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            _selectedRange
                .flatMapLatest { range ->
                    val displayFromMs = range.fromMs()
                    val displayStartDayMs = truncateToDayMs(displayFromMs)
                    // Fetch extra history so chronic (42-day) window is valid from day 1 of the range.
                    // Minor DST imprecision here (up to 1h) is fine for a DB lower-bound query.
                    val fetchFromMs = displayStartDayMs - TimeUnit.DAYS.toMillis(CHRONIC_DAYS.toLong())

                    combine(
                        dailySummaryDao.observeLatest(),
                        workoutDao.observeSince(fetchFromMs),
                    ) { latest, allWorkouts ->
                        val trimpByDay: Map<Long, Float> = allWorkouts
                            .groupBy { truncateToDayMs(it.startTime) }
                            .mapValues { (_, ws) -> ws.sumOf { it.trimp.toDouble() }.toFloat() }

                        val nowDayMs = truncateToDayMs(System.currentTimeMillis())

                        // Build display-day midnights using Calendar.add so DST transitions
                        // don't shift subsequent days by ±1 hour.
                        val displayDayMidnights = buildList<Long> {
                            val cal = Calendar.getInstance()
                            cal.timeInMillis = displayStartDayMs
                            while (cal.timeInMillis <= nowDayMs) {
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

                            val acuteFrom = Calendar.getInstance().run {
                                timeInMillis = dayMidnight
                                add(Calendar.DAY_OF_YEAR, -ACUTE_DAYS)
                                timeInMillis
                            }
                            val chronicFrom = Calendar.getInstance().run {
                                timeInMillis = dayMidnight
                                add(Calendar.DAY_OF_YEAR, -CHRONIC_DAYS)
                                timeInMillis
                            }

                            val acuteSum = trimpByDay.filterKeys { it in acuteFrom..dayMidnight }.values.sum()
                            val chronicSum = trimpByDay.filterKeys { it in chronicFrom..dayMidnight }.values.sum()

                            if (chronicSum > 0f) {
                                val sr = (acuteSum / ACUTE_DAYS) / (chronicSum / CHRONIC_DAYS)
                                dailyStrainRatio.add(DailyDataPoint(dayOffset = i, value = sr))
                            }
                        }

                        WorkoutsUiState(
                            latestSummary = latest,
                            dailyTrimp = dailyTrimp,
                            dailyStrainRatio = dailyStrainRatio,
                            recentWorkouts = allWorkouts.filter { it.startTime >= displayFromMs },
                            selectedRange = range,
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
    }
