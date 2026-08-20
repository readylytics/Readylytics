package app.readylytics.health.feature.vitals.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.util.UnitConverter
import app.readylytics.health.core.scoring.domain.calculation.HealthMetricsCalculator
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.core.ui.common.WeightHistoryItem
import app.readylytics.health.core.ui.common.bucketBy
import app.readylytics.health.core.ui.common.buildPeriodAverageSummary
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.WeightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import app.readylytics.health.core.ui.R as CoreUiR

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
    val periodSummary: PeriodAverageSummary? = null,
    val weightDisplay: String? = null,
    val bmiDisplay: String? = null,
    val historyItems: List<WeightHistoryItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLoading: Boolean = true,
    val deltaWeightDisplay: UiText? = null,
)

@HiltViewModel
class WeightDetailViewModel
    @Inject
    constructor(
        private val weightRepository: WeightRepository,
        private val settingsRepo: UserPreferencesReader,
        private val selectedDateRepository: SelectedDateStore,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val selectedRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)
        private val currentPageFlow = MutableStateFlow(1)

        private data class WeightParams(
            val range: TimeRange,
            val date: LocalDate,
            val page: Int,
            val prefs: UserPreferences,
        )

        val uiState: StateFlow<WeightDetailUiState> =
            combine(
                selectedRangeFlow,
                selectedDateRepository.selectedDate,
                currentPageFlow,
                settingsRepo.userPreferences,
            ) { range, selectedDate, page, userPrefs ->
                WeightParams(range, selectedDate, page, userPrefs)
            }.scan(null as WeightParams?) { prev, current ->
                if (prev != null && (prev.range != current.range || prev.date != current.date)) {
                    currentPageFlow.value = 1
                    current.copy(page = 1)
                } else {
                    current
                }
            }.filterNotNull()
                .distinctUntilChanged()
                .map { params -> withContext(ioDispatcher) { buildState(params) } }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = WeightDetailUiState(),
                )

        private suspend fun buildState(params: WeightParams): WeightDetailUiState {
            val range = params.range
            val selectedDate = params.date
            val page = params.page
            val userPrefs = params.prefs

            val zoneId = ZoneId.systemDefault()
            val rangeStart =
                selectedDate.minusDays((range.days - 1).toLong()).atStartOfDay(zoneId).toInstant()
            val rangeEnd = selectedDate.plusDays(1).atStartOfDay(zoneId).toInstant()

            val records = weightRepository.getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
            val latest = weightRepository.getLatest()
            val previous = latest?.let { weightRepository.getPrevious(it.time.toEpochMilli()) }
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
                            CoreUiR.string.unit_kg
                        } else {
                            CoreUiR.string.unit_lbs
                        }
                    when {
                        diffKg > 0f ->
                            UiText.Compound(
                                listOf(
                                    UiText.StringRes(CoreUiR.string.delta_up),
                                    UiText.RawString(" $formattedDiff "),
                                    UiText.StringRes(unitRes),
                                ),
                            )
                        diffKg < 0f ->
                            UiText.Compound(
                                listOf(
                                    UiText.StringRes(CoreUiR.string.delta_down),
                                    UiText.RawString(" $formattedDiff "),
                                    UiText.StringRes(unitRes),
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

            val startDate = rangeStart.atZone(zoneId).toLocalDate()
            val dailyWeightsRaw =
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

            val dailyWeights =
                if (range.granularity == TrendGranularity.DAILY) {
                    dailyWeightsRaw.padToRange(range.days)
                } else {
                    dailyWeightsRaw.bucketBy(range.granularity, startDate, selectedDate, valueDecimalPlaces = 1)
                }
            val periodSummary =
                if (range.granularity == TrendGranularity.DAILY) {
                    null
                } else {
                    buildPeriodAverageSummary(dailyWeights, range.granularity, startDate)
                }

            val heightCm = userPrefs.heightCm
            val bmi =
                if (latest != null && heightCm != null) {
                    latest.weightKg / ((heightCm / 100f) * (heightCm / 100f))
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

            val totalCount =
                weightRepository.countByDateRange(
                    rangeStart.toEpochMilli(),
                    rangeEnd.toEpochMilli(),
                )
            val totalPages = maxOf(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE)
            val clampedPage = page.coerceIn(1, totalPages)
            val offset = (clampedPage - 1) * PAGE_SIZE

            val pagedRecords =
                weightRepository.getByDateRangePaged(
                    rangeStart.toEpochMilli(),
                    rangeEnd.toEpochMilli(),
                    PAGE_SIZE,
                    offset,
                )

            // The full-range records still drive the chart series and the per-row delta, which
            // for the first record of a page may reference a record on a previous (older) page.
            val recordsAscending = records.sortedBy { it.time }
            val previousById =
                recordsAscending
                    .mapIndexed { index, record -> record.id to recordsAscending.getOrNull(index - 1) }
                    .toMap()

            val historyItems =
                pagedRecords
                    .map { record ->
                        val previous = previousById[record.id]
                        val deltaKg = previous?.let { record.weightKg - it.weightKg }
                        val toDisplayUnit = { kg: Float ->
                            if (userPrefs.unitSystem == UnitSystem.METRIC) {
                                kg
                            } else {
                                kg * UnitConverter.KG_TO_LBS
                            }
                        }
                        val bmiAssessment =
                            userPrefs.heightCm?.let { heightCm ->
                                BodyCompositionAssessment.assessBmi(
                                    HealthMetricsCalculator.calculateBmi(record.weightKg, heightCm),
                                )
                            }
                        WeightHistoryItem(
                            timestampMs = record.time.toEpochMilli(),
                            weightDisplay = toDisplayUnit(record.weightKg),
                            deltaDisplay = deltaKg?.let(toDisplayUnit),
                            unitSystem = userPrefs.unitSystem,
                            bmiStatus = bmiAssessment?.status,
                            bmiCategory = bmiAssessment?.category,
                        )
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

            return WeightDetailUiState(
                latestWeight = latestWeight,
                latestDate = latest?.time?.atZone(zoneId)?.toLocalDate(),
                bmi = bmi,
                heightCm = userPrefs.heightCm,
                averageWeight = averageWeight,
                selectedRange = range,
                dailyWeights = dailyWeights,
                rangeStartMs = rangeStart.toEpochMilli(),
                unitSystem = userPrefs.unitSystem,
                periodSummary = periodSummary,
                weightDisplay =
                    rawLatestWeight?.let {
                        MetricFormatter.formatWeightNumericOnly(
                            it,
                            userPrefs.unitSystem,
                        )
                    },
                bmiDisplay = bmi?.let { MetricFormatter.formatBmi(it) },
                historyItems = historyItems,
                currentPage = clampedPage,
                totalPages = totalPages,
                isLoading = false,
                deltaWeightDisplay = deltaWeightDisplay,
            )
        }

        fun onRangeSelected(range: TimeRange) {
            selectedRangeFlow.value = range
            currentPageFlow.value = 1
        }

        fun onNextPage() {
            val current = uiState.value.currentPage
            val totalPages = uiState.value.totalPages
            if (current < totalPages) {
                currentPageFlow.value = current + 1
            }
        }

        fun onPreviousPage() {
            val current = uiState.value.currentPage
            currentPageFlow.value = maxOf(1, current - 1)
        }

        companion object {
            private const val PAGE_SIZE = 10
        }
    }
