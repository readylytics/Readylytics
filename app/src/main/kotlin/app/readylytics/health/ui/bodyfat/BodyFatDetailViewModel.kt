package app.readylytics.health.ui.bodyfat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.bodyFatStatus
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.domain.util.UnitConverter
import app.readylytics.health.ui.common.BodyFatHistoryItem
import app.readylytics.health.ui.common.DailyDataPoint
import app.readylytics.health.ui.common.TimeRange
import app.readylytics.health.ui.common.padToRange
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
    val historyItems: List<BodyFatHistoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val deltaBodyFatDisplay: String? = null,
)

@HiltViewModel
class BodyFatDetailViewModel
    @Inject
    constructor(
        private val bodyFatRepository: BodyFatRepository,
        private val weightRepository: WeightRepository,
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
                    val previous =
                        if (latest != null) {
                            bodyFatRepository
                                .getByDateRange(0L, latest.timestampMs - 1)
                                .maxByOrNull { it.timestampMs }
                        } else {
                            null
                        }
                    val deltaBodyFatDisplay =
                        if (latest != null && previous != null) {
                            val diff = latest.bodyFatPercent - previous.bodyFatPercent
                            val formattedDiff = MetricFormatter.formatBodyFatNumericOnly(kotlin.math.abs(diff))
                            when {
                                diff > 0f -> "↑ $formattedDiff%"
                                diff < 0f -> "↓ $formattedDiff%"
                                else -> "= 0%"
                            }
                        } else {
                            null
                        }

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

                    val weightByDay =
                        weightRepository
                            .getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                            .groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zoneId).toLocalDate() }
                            .mapValues { (_, dayRecords) -> dayRecords.maxBy { it.timestampMs } }

                    val historyItems =
                        records
                            .sortedByDescending { it.timestampMs }
                            .map { record ->
                                val recordDate = Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate()
                                val weightKg = weightByDay[recordDate]?.weightKg
                                val leanMassKg = weightKg?.let { it * (1f - record.bodyFatPercent / 100f) }
                                val leanMassDisplay =
                                    leanMassKg?.let {
                                        if (userPrefs.unitSystem == UnitSystem.METRIC) {
                                            it
                                        } else {
                                            it * UnitConverter.KG_TO_LBS
                                        }
                                    }
                                BodyFatHistoryItem(
                                    timestampMs = record.timestampMs,
                                    bodyFatPercent = record.bodyFatPercent,
                                    leanMassDisplay = leanMassDisplay,
                                    unitSystem = userPrefs.unitSystem,
                                    status = bodyFatStatus(record.bodyFatPercent, optimalMax),
                                )
                            }
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
                        bodyFatDisplay = latest?.bodyFatPercent?.let { MetricFormatter.formatBodyFatNumericOnly(it) },
                        optimalRangeDisplay =
                            if (optimalMax > 0f) {
                                "0–${MetricFormatter.formatBodyFat(optimalMax)}"
                            } else {
                                null
                            },
                        historyItems = historyItems,
                        isLoading = false,
                        deltaBodyFatDisplay = deltaBodyFatDisplay,
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
