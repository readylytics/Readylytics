package com.gregor.lauritz.healthdashboard.ui.steps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class StepDetailUiState(
    val latestSummary: DailySummaryEntity? = null,
    val dailySteps: List<DailyDataPoint> = emptyList(),
    val stepGoal: Int = 10000,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val rangeStartMs: Long = 0,
)

@HiltViewModel
class StepDetailViewModel @Inject constructor(
    private val dailySummaryDao: DailySummaryDao,
    private val selectedDateRepository: SelectedDateRepository,
    private val prefsRepo: UserPreferencesRepository,
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StepDetailUiState> = combine(
        _selectedRange,
        selectedDateRepository.selectedDate
    ) { range, date -> range to date }
        .flatMapLatest { (range, date) ->
            val fromMs = range.fromMs(date)
            val startDayMs = truncateToDayMs(fromMs)

            combine(
                dailySummaryDao.observeLatest(),
                dailySummaryDao.observeSince(fromMs),
                prefsRepo.userPreferences
            ) { latest, history, prefs ->
                val points = history
                    .filter { it.stepCount != null }
                    .map { summary ->
                        val dayMs = summary.dateMidnightMs
                        val dayOffset = ChronoUnit.DAYS.between(
                            Instant.ofEpochMilli(startDayMs).atZone(ZoneId.systemDefault()).toLocalDate(),
                            Instant.ofEpochMilli(dayMs).atZone(ZoneId.systemDefault()).toLocalDate()
                        ).toInt()
                        DailyDataPoint(dayOffset, summary.stepCount!!.toFloat())
                    }
                    .sortedBy { it.dayOffset }

                StepDetailUiState(
                    latestSummary = latest,
                    dailySteps = points,
                    stepGoal = prefs.stepGoal,
                    selectedRange = range,
                    rangeStartMs = startDayMs
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StepDetailUiState()
        )

    fun onRangeSelected(range: TimeRange) {
        _selectedRange.value = range
    }

    private fun truncateToDayMs(ms: Long): Long {
        return Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
