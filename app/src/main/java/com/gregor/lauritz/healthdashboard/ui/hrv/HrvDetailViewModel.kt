package com.gregor.lauritz.healthdashboard.ui.hrv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.domain.model.hrvStatus
import com.gregor.lauritz.healthdashboard.domain.model.hrvZoneBands
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
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

private const val HRV_WARNING_MULTIPLIER = 0.7f

data class HrvDetailUiState(
    val latestSummary: DailySummary? = null,
    val dailyHrv: List<DailyDataPoint> = emptyList(),
    val hrvBaseline: Float? = null,
    val hrvStatus: MetricStatus? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val rangeStartMs: Long = 0,
    val hrvZoneBands: List<ZoneBand>? = null,
    val isCalibrating: Boolean = false,
)

@HiltViewModel
class HrvDetailViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<HrvDetailUiState> =
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
                                .filter { it.nocturnalHrv != null }
                                .map { summary ->
                                    val d = summary.date
                                    val dayOffset =
                                        ChronoUnit.DAYS
                                            .between(
                                                Instant
                                                    .ofEpochMilli(startDayMs)
                                                    .atZone(ZoneId.systemDefault())
                                                    .toLocalDate(),
                                                d,
                                            ).toInt()
                                    DailyDataPoint(dayOffset, summary.nocturnalHrv!!.toFloat())
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        val baselineVal = latest?.hrvBaseline?.toFloat() ?: prefs.hrvBaselineOverride
                        val calibrating = latest?.isCalibrating ?: false

                        val bands =
                            baselineVal?.let { baseline ->
                                hrvZoneBands(
                                    optimalMin = baseline * prefs.hrvOptimalThreshold,
                                    neutralMin = baseline * prefs.hrvWarningThreshold,
                                    warningMin = baseline * prefs.hrvWarningThreshold * HRV_WARNING_MULTIPLIER,
                                )
                            }

                        HrvDetailUiState(
                            latestSummary = latest,
                            dailyHrv = points,
                            hrvBaseline = baselineVal,
                            hrvStatus =
                                if (calibrating) {
                                    MetricStatus.CALIBRATING
                                } else {
                                    latest?.hrvStatus(prefs.hrvOptimalThreshold, prefs.hrvWarningThreshold)
                                },
                            selectedRange = range,
                            rangeStartMs = startDayMs,
                            hrvZoneBands = bands,
                            isCalibrating = calibrating,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = HrvDetailUiState(),
                )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }
    }
