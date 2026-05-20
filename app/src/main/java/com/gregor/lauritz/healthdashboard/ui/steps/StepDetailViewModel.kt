package com.gregor.lauritz.healthdashboard.ui.steps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.common.padToRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class StepDetailUiState(
    val latestSummary: DailySummary? = null,
    val dailySteps: List<DailyDataPoint> = emptyList(),
    val stepGoal: Int = 10000,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val rangeStartMs: Long = 0,
)

@HiltViewModel
class StepDetailViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val selectedDateRepository: SelectedDateRepository,
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<StepDetailUiState> =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
            ) { range, date -> range to date }
                .flatMapLatest { (range, date) ->
                    val fromMs = range.fromMs(date)
                    val startDayMs = fromMs.truncateToDayMs()
                    val selectedDateMidnightMs = date.toMidnightEpochMilli()

                    combine(
                        dailySummaryDao
                            .observeByDate(
                                selectedDateMidnightMs,
                            ).map { it?.let { DailySummaryMapper.toDomain(it) } },
                        dailySummaryDao.observeSince(fromMs).map { list ->
                            list.map { DailySummaryMapper.toDomain(it) }
                        },
                        settingsRepo.userPreferences,
                    ) { latest, history, prefs ->
                        val points =
                            history
                                .filter { it.stepCount != null }
                                .map { summary ->
                                    val d = summary.date
                                    val dayOffset =
                                        ChronoUnit.DAYS
                                            .between(
                                                Instant
                                                    .ofEpochMilli(
                                                        startDayMs,
                                                    ).atZone(ZoneId.systemDefault())
                                                    .toLocalDate(),
                                                d,
                                            ).toInt()
                                    DailyDataPoint(dayOffset, summary.stepCount!!.toFloat())
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        StepDetailUiState(
                            latestSummary = latest,
                            dailySteps = points,
                            stepGoal = prefs.stepGoal,
                            selectedRange = range,
                            rangeStartMs = startDayMs,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = StepDetailUiState(),
                )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }
    }
