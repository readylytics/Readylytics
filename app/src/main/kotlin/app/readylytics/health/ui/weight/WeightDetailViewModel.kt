package app.readylytics.health.ui.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.R
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.calculation.HealthMetricsCalculator
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.domain.util.UnitConverter
import app.readylytics.health.ui.common.DailyDataPoint
import app.readylytics.health.ui.common.TimeRange
import app.readylytics.health.ui.common.UiText
import app.readylytics.health.ui.common.WeightHistoryItem
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
    val historyItems: List<WeightHistoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val deltaWeightDisplay: UiText? = null,
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
                    val previous = if (latest != null) weightRepository.getPrevious(latest.timestampMs) else null
                    val deltaWeightDisplay =
                        if (latest != null && previous != null) {
                            val diffKg = latest.weightKg - previous.weightKg
                            val formattedDiff =
                                MetricFormatter.formatWeightNumericOnly(
                                    kotlin.math.abs(diffKg),
                                    userPrefs.unitSystem,
                                )
                            val unitRes =
                                if (userPrefs.unitSystem ==
                                    UnitSystem.METRIC
                                ) {
                                    R.string.unit_kg
                                } else {
                                    R.string.unit_lbs
                                }
                            when {
                                diffKg > 0f ->
                                    UiText.Compound(
                                        listOf(
                                            UiText.StringRes(R.string.delta_up),
                                            UiText.RawString(" $formattedDiff "),
                                            UiText.StringRes(unitRes),
                                        ),
                                    )
                                diffKg < 0f ->
                                    UiText.Compound(
                                        listOf(
                                            UiText.StringRes(R.string.delta_down),
                                            UiText.RawString(" $formattedDiff "),
                                            UiText.StringRes(unitRes),
                                        ),
                                    )
                                else -> UiText.StringRes(R.string.delta_no_change)
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

                    val recordsAscending = records.sortedBy { it.timestampMs }
                    val historyItems =
                        recordsAscending
                            .mapIndexed { index, record ->
                                val previous = if (index > 0) recordsAscending[index - 1] else null
                                val deltaKg = previous?.let { record.weightKg - it.weightKg }
                                val toDisplayUnit = { kg: Float ->
                                    if (userPrefs.unitSystem == UnitSystem.METRIC) {
                                        kg
                                    } else {
                                        kg * UnitConverter.KG_TO_LBS
                                    }
                                }
                                val bmiStatus =
                                    userPrefs.heightCm?.let { heightCm ->
                                        HealthMetricsCalculator
                                            .assessBmi(HealthMetricsCalculator.calculateBmi(record.weightKg, heightCm))
                                    }
                                WeightHistoryItem(
                                    timestampMs = record.timestampMs,
                                    weightDisplay = toDisplayUnit(record.weightKg),
                                    deltaDisplay = deltaKg?.let(toDisplayUnit),
                                    unitSystem = userPrefs.unitSystem,
                                    bmiStatus = bmiStatus,
                                )
                            }.reversed()

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
                        weightDisplay =
                            rawLatestWeight?.let {
                                MetricFormatter.formatWeightNumericOnly(
                                    it,
                                    userPrefs.unitSystem,
                                )
                            },
                        bmiDisplay = bmi?.let { MetricFormatter.formatBmi(it) },
                        historyItems = historyItems,
                        isLoading = false,
                        deltaWeightDisplay = deltaWeightDisplay,
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
