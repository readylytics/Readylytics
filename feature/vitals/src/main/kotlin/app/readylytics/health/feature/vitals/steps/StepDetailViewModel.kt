package app.readylytics.health.feature.vitals.steps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.di.DefaultDispatcher
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.util.toMidnightEpochMilli
import app.readylytics.health.domain.util.truncateToDayMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
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
    val isLoading: Boolean = true,
)

@HiltViewModel
class StepDetailViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val selectedDateRepository: SelectedDateStore,
        private val settingsRepo: UserPreferencesReader,
        @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
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
                        dailySummaryRepository.observeByDate(selectedDateMidnightMs),
                        dailySummaryRepository.observeSince(fromMs),
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
                                    // Allow-listed: chart-axis geometry for plotted step series, not a display metric
                                    DailyDataPoint(dayOffset, summary.stepCount!!.toFloat())
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        StepDetailUiState(
                            latestSummary = latest,
                            dailySteps = points,
                            stepGoal = prefs.stepGoal,
                            selectedRange = range,
                            rangeStartMs = startDayMs,
                            isLoading = false,
                        )
                    }
                }
                // Keep the map/sort/padToRange transform off the (Main) collector context.
                .flowOn(defaultDispatcher)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = StepDetailUiState(),
                )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }
    }
