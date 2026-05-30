package com.gregor.lauritz.healthdashboard.ui.rhr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.domain.model.restingHrStatus
import com.gregor.lauritz.healthdashboard.domain.model.rhrZoneBands
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.common.padToRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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

data class RestingHrDetailUiState(
    val latestSummary: DailySummary? = null,
    val dailyRhr: List<DailyDataPoint> = emptyList(),
    val rhrBaseline: Float? = null,
    val rhrStatus: MetricStatus? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val rangeStartMs: Long = 0,
    val rhrZoneBands: List<ZoneBand>? = null,
)

@HiltViewModel
class RestingHrDetailViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<RestingHrDetailUiState> =
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
                                .filter { it.restingHeartRate != null || it.nocturnalRhr != null }
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
                                    DailyDataPoint(
                                        dayOffset,
                                        (summary.restingHeartRate ?: summary.nocturnalRhr)!!.toFloat(),
                                    )
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        RestingHrDetailUiState(
                            latestSummary = latest,
                            dailyRhr = points,
                            rhrBaseline = latest?.restingHrBaseline?.toFloat(),
                            rhrStatus = latest?.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold),
                            selectedRange = range,
                            rangeStartMs = startDayMs,
                            rhrZoneBands = rhrZoneBands(
                                optimalMax = prefs.rhrOptimalThreshold,
                                neutralMax = prefs.rhrWarningThreshold,
                                warningMax = prefs.rhrWarningThreshold * 1.3f,
                            ),
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = RestingHrDetailUiState(),
                )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }
    }
