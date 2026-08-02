package app.readylytics.health.feature.vitals.bodyfat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.BodyFatHistoryItem
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.domain.util.UnitConverter
import app.readylytics.health.feature.vitals.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import app.readylytics.health.core.ui.R as CoreUiR

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
    val deltaBodyFatDisplay: UiText? = null,
)

@HiltViewModel
class BodyFatDetailViewModel
    @Inject
    constructor(
        private val bodyFatRepository: BodyFatRepository,
        private val weightRepository: WeightRepository,
        private val settingsRepo: UserPreferencesReader,
        private val selectedDateRepository: SelectedDateStore,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val selectedRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

        val uiState: StateFlow<BodyFatDetailUiState> =
            combine(
                selectedRangeFlow,
                selectedDateRepository.selectedDate,
                settingsRepo.userPreferences,
            ) { range, selectedDate, userPrefs ->
                withContext(ioDispatcher) {
                    val zoneId = ZoneId.systemDefault()
                    val rangeStart =
                        selectedDate.minusDays((range.days - 1).toLong()).atStartOfDay(zoneId).toInstant()
                    val rangeEnd = selectedDate.plusDays(1).atStartOfDay(zoneId).toInstant()

                    val records = bodyFatRepository.getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                    val latest = bodyFatRepository.getLatest()
                    val previous = latest?.let { bodyFatRepository.getPrevious(it.time.toEpochMilli()) }
                    val deltaBodyFatDisplay =
                        if (latest != null && previous != null) {
                            val diff = latest.bodyFatPercent - previous.bodyFatPercent
                            val formattedDiff = MetricFormatter.formatBodyFatNumericOnly(kotlin.math.abs(diff))
                            when {
                                diff > 0 ->
                                    UiText.Compound(
                                        listOf(
                                            UiText.StringRes(CoreUiR.string.delta_up),
                                            UiText.RawString(" $formattedDiff"),
                                            UiText.StringRes(app.readylytics.health.core.ui.R.string.unit_percent),
                                        ),
                                    )
                                diff < 0 ->
                                    UiText.Compound(
                                        listOf(
                                            UiText.StringRes(CoreUiR.string.delta_down),
                                            UiText.RawString(" $formattedDiff"),
                                            UiText.StringRes(app.readylytics.health.core.ui.R.string.unit_percent),
                                        ),
                                    )
                                else -> UiText.StringRes(CoreUiR.string.delta_no_change)
                            }
                        } else {
                            null
                        }

                    val recordsByDay =
                        records.groupBy { record ->
                            ChronoUnit.DAYS
                                .between(
                                    rangeStart.atZone(zoneId).toLocalDate(),
                                    record.time.atZone(zoneId).toLocalDate(),
                                ).toInt()
                        }

                    val dailyBodyFat =
                        recordsByDay
                            .map { (dayOffset, dayRecords) ->
                                val avgBodyFat = dayRecords.map { it.bodyFatPercent }.average().toFloat()
                                DailyDataPoint(dayOffset, avgBodyFat)
                            }.sortedBy { it.dayOffset }
                            .padToRange(range.days)

                    val latestAssessment =
                        latest?.let {
                            BodyCompositionAssessment.assessBodyFat(
                                bodyFatPercent = it.bodyFatPercent,
                                physiologyProfile = userPrefs.physiologyProfile,
                                gender = userPrefs.gender,
                            )
                        }
                    val reference =
                        latestAssessment?.reference
                            ?: BodyCompositionAssessment
                                .assessBodyFat(
                                    bodyFatPercent = 0f,
                                    physiologyProfile = userPrefs.physiologyProfile,
                                    gender = userPrefs.gender,
                                ).reference
                    val optimalMin = reference.axisMinimum
                    val optimalMax = reference.axisMaximum
                    val status = latestAssessment?.status?.toMetricStatus()

                    val weightByDay =
                        weightRepository
                            .getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                            .groupBy { it.time.atZone(zoneId).toLocalDate() }
                            .mapValues { (_, dayRecords) -> dayRecords.maxBy { it.time } }

                    val historyItems =
                        records
                            .sortedByDescending { it.time }
                            .map { record ->
                                val recordDate = record.time.atZone(zoneId).toLocalDate()
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
                                val assessment =
                                    BodyCompositionAssessment.assessBodyFat(
                                        bodyFatPercent = record.bodyFatPercent,
                                        physiologyProfile = userPrefs.physiologyProfile,
                                        gender = userPrefs.gender,
                                    )
                                BodyFatHistoryItem(
                                    timestampMs = record.time.toEpochMilli(),
                                    bodyFatPercent = record.bodyFatPercent,
                                    leanMassDisplay = leanMassDisplay,
                                    unitSystem = userPrefs.unitSystem,
                                    status = assessment.status.toMetricStatus(),
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
                        latestDate = latest?.time?.atZone(zoneId)?.toLocalDate(),
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
    }
