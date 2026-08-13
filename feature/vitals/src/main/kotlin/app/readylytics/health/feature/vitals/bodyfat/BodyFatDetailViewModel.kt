package app.readylytics.health.feature.vitals.bodyfat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.BodyFatHistoryItem
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.core.ui.common.bucketBy
import app.readylytics.health.core.ui.common.buildPeriodAverageSummary
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.ZoneBand
import app.readylytics.health.domain.model.bodyFatZoneBands
import app.readylytics.health.domain.model.toMetricStatus
import app.readylytics.health.domain.preferences.Gender
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

data class BodyFatDetailUiState(
    val latestBodyFat: Float? = null,
    val latestDate: LocalDate? = null,
    val gender: Gender? = null,
    val referenceAxisMinimum: Float = 0f,
    val referenceAxisMaximum: Float = 0f,
    val referenceMidpoint: Float = 0f,
    val chartZoneBands: List<ZoneBand> = emptyList(),
    val bodyFatStatus: MetricStatus? = null,
    val averageBodyFat: Float? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailyBodyFat: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
    val periodSummary: PeriodAverageSummary? = null,
    val bodyFatDisplay: String? = null,
    val historyItems: List<BodyFatHistoryItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
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
        private val currentPageFlow = MutableStateFlow(1)

        private data class BodyFatParams(
            val range: TimeRange,
            val date: LocalDate,
            val page: Int,
            val prefs: UserPreferences,
        )

        val uiState: StateFlow<BodyFatDetailUiState> =
            combine(
                selectedRangeFlow,
                selectedDateRepository.selectedDate,
                currentPageFlow,
                settingsRepo.userPreferences,
            ) { range, selectedDate, page, userPrefs ->
                BodyFatParams(range, selectedDate, page, userPrefs)
            }.scan(null as BodyFatParams?) { prev, current ->
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
                    initialValue = BodyFatDetailUiState(),
                )

        private suspend fun buildState(params: BodyFatParams): BodyFatDetailUiState {
            val range = params.range
            val selectedDate = params.date
            val page = params.page
            val userPrefs = params.prefs

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

            val startDate = rangeStart.atZone(zoneId).toLocalDate()
            val dailyBodyFatRaw =
                recordsByDay
                    .map { (dayOffset, dayRecords) ->
                        val avgBodyFat = dayRecords.map { it.bodyFatPercent }.average().toFloat()
                        DailyDataPoint(dayOffset, avgBodyFat)
                    }.sortedBy { it.dayOffset }

            val dailyBodyFat =
                if (range.granularity == TrendGranularity.DAILY) {
                    dailyBodyFatRaw.padToRange(range.days)
                } else {
                    dailyBodyFatRaw.bucketBy(range.granularity, startDate, selectedDate, valueDecimalPlaces = 1)
                }
            val periodSummary =
                if (range.granularity == TrendGranularity.DAILY) {
                    null
                } else {
                    buildPeriodAverageSummary(dailyBodyFat, range.granularity, startDate)
                }

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
                    ?: BodyCompositionAssessment.bodyFatReference(
                        physiologyProfile = userPrefs.physiologyProfile,
                        gender = userPrefs.gender,
                    )
            val status = latestAssessment?.status?.toMetricStatus()

            val weightByDay =
                weightRepository
                    .getByDateRange(rangeStart.toEpochMilli(), rangeEnd.toEpochMilli())
                    .groupBy { it.time.atZone(zoneId).toLocalDate() }
                    .mapValues { (_, dayRecords) -> dayRecords.maxBy { it.time } }

            val totalCount =
                bodyFatRepository.countByDateRange(
                    rangeStart.toEpochMilli(),
                    rangeEnd.toEpochMilli(),
                )
            val totalPages = maxOf(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE)
            val clampedPage = page.coerceIn(1, totalPages)
            val offset = (clampedPage - 1) * PAGE_SIZE

            val pagedRecords =
                bodyFatRepository.getByDateRangePaged(
                    rangeStart.toEpochMilli(),
                    rangeEnd.toEpochMilli(),
                    PAGE_SIZE,
                    offset,
                )

            val historyItems =
                pagedRecords
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
                            category = assessment.category,
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
            return BodyFatDetailUiState(
                latestBodyFat = latest?.bodyFatPercent,
                latestDate = latest?.time?.atZone(zoneId)?.toLocalDate(),
                gender = userPrefs.gender,
                referenceAxisMinimum = reference.axisMinimum,
                referenceAxisMaximum = reference.axisMaximum,
                referenceMidpoint = reference.referenceMidpoint,
                chartZoneBands = bodyFatZoneBands(userPrefs.physiologyProfile, userPrefs.gender),
                bodyFatStatus = status,
                averageBodyFat = average,
                selectedRange = range,
                dailyBodyFat = dailyBodyFat,
                rangeStartMs = rangeStart.toEpochMilli(),
                periodSummary = periodSummary,
                bodyFatDisplay = latest?.bodyFatPercent?.let { MetricFormatter.formatBodyFatNumericOnly(it) },
                historyItems = historyItems,
                currentPage = clampedPage,
                totalPages = totalPages,
                isLoading = false,
                deltaBodyFatDisplay = deltaBodyFatDisplay,
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
