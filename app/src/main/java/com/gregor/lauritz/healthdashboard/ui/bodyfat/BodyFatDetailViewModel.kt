package com.gregor.lauritz.healthdashboard.ui.bodyfat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.calculation.HealthMetricsCalculator
import com.gregor.lauritz.healthdashboard.domain.repository.BodyFatRepository
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

data class BodyFatDetailUiState(
    val latestBodyFat: Float? = null,
    val latestDate: LocalDate? = null,
    val age: Int = 30,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailyBodyFat: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
)

@HiltViewModel
class BodyFatDetailViewModel
    @Inject
    constructor(
        private val bodyFatRepository: BodyFatRepository,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)

        val uiState: StateFlow<BodyFatDetailUiState> =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
                settingsRepo.userPreferences,
            ) { range, selectedDate, userPrefs ->
                val zoneId = ZoneId.systemDefault()
                val rangeStart =
                    selectedDate.minusDays((range.days - 1).toLong()).atStartOfDay(zoneId).toInstant()
                val rangeEnd = selectedDate.plusDays(1).atStartOfDay(zoneId).toInstant()

                val records = bodyFatRepository.getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                val latest = bodyFatRepository.getLatest()

                val dailyBodyFat =
                    records.map { record ->
                        val dayOffset =
                            ChronoUnit.DAYS.between(
                                rangeStart.atZone(zoneId).toLocalDate(),
                                Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate(),
                            ).toInt()
                        DailyDataPoint(dayOffset, record.bodyFatPercent)
                    }.padToRange(range.days)

                BodyFatDetailUiState(
                    latestBodyFat = latest?.bodyFatPercent,
                    latestDate = latest?.timestampMs?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() },
                    age = userPrefs.age,
                    selectedRange = range,
                    dailyBodyFat = dailyBodyFat,
                    rangeStartMs = rangeStart.toEpochMilli(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BodyFatDetailUiState(),
            )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }
    }
