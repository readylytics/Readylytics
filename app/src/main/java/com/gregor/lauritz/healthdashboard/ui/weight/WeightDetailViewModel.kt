package com.gregor.lauritz.healthdashboard.ui.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.WeightRepository
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

data class WeightDetailUiState(
    val latestWeight: Float? = null,
    val latestDate: LocalDate? = null,
    val bmi: Float? = null,
    val heightCm: Float? = null,
    val averageWeight: Float? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailyWeights: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
)

@HiltViewModel
class WeightDetailViewModel
    @Inject
    constructor(
        private val weightRepository: WeightRepository,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
    ) : ViewModel() {
        private val selectedRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

        val uiState: StateFlow<WeightDetailUiState> =
            combine(
                selectedRangeFlow,
                selectedDateRepository.selectedDate,
                settingsRepo.userPreferences,
            ) { range, selectedDate, userPrefs ->
                val zoneId = ZoneId.systemDefault()
                val rangeStart =
                    selectedDate.minusDays((range.days - 1).toLong()).atStartOfDay(zoneId).toInstant()
                val rangeEnd = selectedDate.plusDays(1).atStartOfDay(zoneId).toInstant()

                val records = weightRepository.getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                val latest = weightRepository.getLatest()

                val dailyWeights =
                    records
                        .map { record ->
                            val dayOffset =
                                ChronoUnit.DAYS
                                    .between(
                                        rangeStart.atZone(zoneId).toLocalDate(),
                                        Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate(),
                                    ).toInt()
                            DailyDataPoint(dayOffset, record.weightKg)
                        }.padToRange(range.days)

                val bmi =
                    if (latest != null && userPrefs.heightCm != null) {
                        latest.weightKg / ((userPrefs.heightCm / 100f) * (userPrefs.heightCm / 100f))
                    } else {
                        null
                    }

                val average = if (records.isNotEmpty()) records.map { it.weightKg }.average().toFloat() else null

                WeightDetailUiState(
                    latestWeight = latest?.weightKg,
                    latestDate = latest?.timestampMs?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() },
                    bmi = bmi,
                    heightCm = userPrefs.heightCm,
                    averageWeight = average,
                    selectedRange = range,
                    dailyWeights = dailyWeights,
                    rangeStartMs = rangeStart.toEpochMilli(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = WeightDetailUiState(),
            )

        fun onRangeSelected(range: TimeRange) {
            selectedRangeFlow.value = range
        }
    }
