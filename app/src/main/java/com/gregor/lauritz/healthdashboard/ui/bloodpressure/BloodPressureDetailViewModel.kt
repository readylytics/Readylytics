package com.gregor.lauritz.healthdashboard.ui.bloodpressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.BloodPressureRepository
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.common.padToRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class BloodPressureDetailUiState(
    val latestSystolic: Int? = null,
    val latestDiastolic: Int? = null,
    val latestDate: LocalDate? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailySystolic: List<DailyDataPoint> = emptyList(),
    val dailyDiastolic: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
)

@HiltViewModel
class BloodPressureDetailViewModel
    @Inject
    constructor(
        private val bloodPressureRepository: BloodPressureRepository,
        private val selectedDateRepository: SelectedDateRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)

        val uiState: StateFlow<BloodPressureDetailUiState> =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
            ) { range, selectedDate ->
                val zoneId = ZoneId.systemDefault()
                val rangeStart =
                    selectedDate.minusDays((range.days - 1).toLong()).atStartOfDay(zoneId).toInstant()
                val rangeEnd = selectedDate.plusDays(1).atStartOfDay(zoneId).toInstant()

                val records =
                    bloodPressureRepository.getByDateRange(
                        rangeStart.toEpochMilli(),
                        rangeEnd.toEpochMilli(),
                    )
                val latest = bloodPressureRepository.getLatest()

                val dailySystolic =
                    records.map { record ->
                        val dayOffset =
                            ChronoUnit.DAYS.between(
                                rangeStart.atZone(zoneId).toLocalDate(),
                                Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate(),
                            ).toInt()
                        DailyDataPoint(dayOffset, record.systolicMmHg.toFloat())
                    }.padToRange(range.days)

                val dailyDiastolic =
                    records.map { record ->
                        val dayOffset =
                            ChronoUnit.DAYS.between(
                                rangeStart.atZone(zoneId).toLocalDate(),
                                Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate(),
                            ).toInt()
                        DailyDataPoint(dayOffset, record.diastolicMmHg.toFloat())
                    }.padToRange(range.days)

                BloodPressureDetailUiState(
                    latestSystolic = latest?.systolicMmHg,
                    latestDiastolic = latest?.diastolicMmHg,
                    latestDate = Instant.ofEpochMilli(latest?.timestampMs ?: 0).atZone(zoneId).toLocalDate(),
                    selectedRange = range,
                    dailySystolic = dailySystolic,
                    dailyDiastolic = dailyDiastolic,
                    rangeStartMs = rangeStart.toEpochMilli(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BloodPressureDetailUiState(),
            )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }
    }
