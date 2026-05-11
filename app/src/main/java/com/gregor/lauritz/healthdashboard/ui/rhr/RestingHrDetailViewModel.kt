package com.gregor.lauritz.healthdashboard.ui.rhr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.domain.model.restingHrStatus
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
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
)

@HiltViewModel
class RestingHrDetailViewModel @Inject constructor(
    private val dailySummaryDao: DailySummaryDao,
    private val selectedDateRepository: SelectedDateRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<RestingHrDetailUiState> = combine(
        _selectedRange,
        selectedDateRepository.selectedDate
    ) { range, date -> range to date }
        .flatMapLatest { (range, date) ->
            val fromMs = range.fromMs(date)
            val startDayMs = fromMs.truncateToDayMs()

            combine(
                dailySummaryDao.observeLatest().map { it?.let { DailySummaryMapper.toDomain(it) } },
                dailySummaryDao.observeSince(fromMs).map { list -> list.map { DailySummaryMapper.toDomain(it) } },
                settingsRepo.userPreferences
            ) { latest, history, prefs ->
                val points = history
                    .filter { it.restingHeartRate != null || it.nocturnalRhr != null }
                    .map { summary ->
                        val d = summary.date
                        val dayOffset = ChronoUnit.DAYS.between(
                            Instant.ofEpochMilli(startDayMs).atZone(ZoneId.systemDefault()).toLocalDate(),
                            d
                        ).toInt()
                        DailyDataPoint(dayOffset, (summary.restingHeartRate ?: summary.nocturnalRhr)!!.toFloat())
                    }
                    .sortedBy { it.dayOffset }

                RestingHrDetailUiState(
                    latestSummary = latest,
                    dailyRhr = points,
                    rhrBaseline = latest?.restingHrBaseline?.toFloat(),
                    rhrStatus = latest?.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold),
                    selectedRange = range,
                    rangeStartMs = startDayMs
                )
            }.flowOn(Dispatchers.Default)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RestingHrDetailUiState()
        )

    fun onRangeSelected(range: TimeRange) {
        _selectedRange.value = range
    }

}
