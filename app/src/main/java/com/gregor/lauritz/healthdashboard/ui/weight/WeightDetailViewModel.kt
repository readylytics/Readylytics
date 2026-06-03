package com.gregor.lauritz.healthdashboard.ui.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.display.MetricFormatter
import com.gregor.lauritz.healthdashboard.domain.repository.WeightRepository
import com.gregor.lauritz.healthdashboard.domain.util.UnitConverter
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.common.padToRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
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
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val weightDisplay: String? = null,
    val bmiDisplay: String? = null,
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
                withContext(Dispatchers.IO) {
                    val zoneId = ZoneId.systemDefault()
                    val rangeStart =
                        selectedDate.minusDays((range.days - 1).toLong()).atStartOfDay(zoneId).toInstant()
                    val rangeEnd = selectedDate.plusDays(1).atStartOfDay(zoneId).toInstant()

                    val records = weightRepository.getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                    val latest = weightRepository.getLatest()

                    val recordsByDay =
                        records.groupBy { record ->
                            ChronoUnit.DAYS
                                .between(
                                    rangeStart.atZone(zoneId).toLocalDate(),
                                    Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate(),
                                ).toInt()
                        }

                    val dailyWeights =
                        recordsByDay
                            .map { (dayOffset, dayRecords) ->
                                val avgWeight = dayRecords.map { it.weightKg }.average().toFloat()
                                val displayWeight =
                                    if (userPrefs.unitSystem == UnitSystem.METRIC) {
                                        avgWeight
                                    } else {
                                        avgWeight * UnitConverter.KG_TO_LBS
                                    }
                                DailyDataPoint(dayOffset, displayWeight)
                            }.sortedBy { it.dayOffset }
                            .padToRange(range.days)

                    val bmi =
                        if (latest != null && userPrefs.heightCm != null) {
                            latest.weightKg / ((userPrefs.heightCm / 100f) * (userPrefs.heightCm / 100f))
                        } else {
                            null
                        }

                    val rawLatestWeight = latest?.weightKg
                    val latestWeight =
                        if (rawLatestWeight != null) {
                            if (userPrefs.unitSystem == UnitSystem.METRIC) {
                                rawLatestWeight
                            } else {
                                rawLatestWeight * UnitConverter.KG_TO_LBS
                            }
                        } else {
                            null
                        }

                    val rawAverage = if (records.isNotEmpty()) records.map { it.weightKg }.average().toFloat() else null
                    val averageWeight =
                        if (rawAverage != null) {
                            if (userPrefs.unitSystem == UnitSystem.METRIC) {
                                rawAverage
                            } else {
                                rawAverage * UnitConverter.KG_TO_LBS
                            }
                        } else {
                            null
                        }

                    WeightDetailUiState(
                        latestWeight = latestWeight,
                        latestDate = latest?.timestampMs?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() },
                        bmi = bmi,
                        heightCm = userPrefs.heightCm,
                        averageWeight = averageWeight,
                        selectedRange = range,
                        dailyWeights = dailyWeights,
                        rangeStartMs = rangeStart.toEpochMilli(),
                        unitSystem = userPrefs.unitSystem,
                        weightDisplay = rawLatestWeight?.let { MetricFormatter.formatWeight(it, userPrefs.unitSystem) },
                        bmiDisplay = bmi?.let { MetricFormatter.formatBmi(it) },
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = WeightDetailUiState(),
            )

        fun onRangeSelected(range: TimeRange) {
            selectedRangeFlow.value = range
        }
    }
