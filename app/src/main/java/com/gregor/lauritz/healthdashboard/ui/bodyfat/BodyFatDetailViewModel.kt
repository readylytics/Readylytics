package com.gregor.lauritz.healthdashboard.ui.bodyfat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.display.MetricFormatter
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.bodyFatStatus
import com.gregor.lauritz.healthdashboard.domain.repository.BodyFatRepository
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

data class BodyFatDetailUiState(
    val latestBodyFat: Float? = null,
    val latestDate: LocalDate? = null,
    val age: Int = 30,
    val gender: String = "Unknown",
    val optimalRangeMin: Float = 0f,
    val optimalRangeMax: Float = 0f,
    val bodyFatStatus: MetricStatus? = null,
    val averageBodyFat: Float? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailyBodyFat: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
    val bodyFatDisplay: String? = null,
    val optimalRangeDisplay: String? = null,
)

@HiltViewModel
class BodyFatDetailViewModel
    @Inject
    constructor(
        private val bodyFatRepository: BodyFatRepository,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
    ) : ViewModel() {
        private val selectedRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

        val uiState: StateFlow<BodyFatDetailUiState> =
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

                    val records = bodyFatRepository.getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                    val latest = bodyFatRepository.getLatest()

                    val recordsByDay =
                        records.groupBy { record ->
                            ChronoUnit.DAYS
                                .between(
                                    rangeStart.atZone(zoneId).toLocalDate(),
                                    Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate(),
                                ).toInt()
                        }

                    val dailyBodyFat =
                        recordsByDay
                            .map { (dayOffset, dayRecords) ->
                                val avgBodyFat = dayRecords.map { it.bodyFatPercent }.average().toFloat()
                                DailyDataPoint(dayOffset, avgBodyFat)
                            }.sortedBy { it.dayOffset }
                            .padToRange(range.days)

                    val (optimalMin, optimalMax) =
                        calculateOptimalRange(
                            userPrefs.age,
                            userPrefs.gender?.name ?: "Unknown",
                        )
                    val status = latest?.bodyFatPercent?.let { bodyFatStatus(it, optimalMax) }
                    val average =
                        if (records.isNotEmpty()) {
                            records
                                .map {
                                    it.bodyFatPercent
                                }.average()
                                .toFloat()
                        } else {
                            null
                        }
                    BodyFatDetailUiState(
                        latestBodyFat = latest?.bodyFatPercent,
                        latestDate = latest?.timestampMs?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() },
                        age = userPrefs.age,
                        gender = userPrefs.gender?.name ?: "Unknown",
                        optimalRangeMin = optimalMin,
                        optimalRangeMax = optimalMax,
                        bodyFatStatus = status,
                        averageBodyFat = average,
                        selectedRange = range,
                        dailyBodyFat = dailyBodyFat,
                        rangeStartMs = rangeStart.toEpochMilli(),
                        bodyFatDisplay = latest?.bodyFatPercent?.let { MetricFormatter.formatBodyFat(it) },
                        optimalRangeDisplay =
                            if (optimalMax >
                                0f
                            ) {
                                "0–${MetricFormatter.formatBodyFat(optimalMax)}"
                            } else {
                                null
                            },
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BodyFatDetailUiState(),
            )

        fun onRangeSelected(range: TimeRange) {
            selectedRangeFlow.value = range
        }

        private fun calculateOptimalRange(
            age: Int,
            genderName: String,
        ): Pair<Float, Float> {
            val ageCoerced = age.coerceIn(1, 120)
            return when (genderName) {
                "MALE" ->
                    when {
                        ageCoerced in 20..40 -> Pair(0f, 19f)
                        ageCoerced in 41..60 -> Pair(0f, 22f)
                        else -> Pair(0f, 24f)
                    }
                "FEMALE" ->
                    when {
                        ageCoerced in 20..40 -> Pair(0f, 32f)
                        ageCoerced in 41..60 -> Pair(0f, 34f)
                        else -> Pair(0f, 36f)
                    }
                else -> Pair(0f, 30f)
            }
        }
    }
