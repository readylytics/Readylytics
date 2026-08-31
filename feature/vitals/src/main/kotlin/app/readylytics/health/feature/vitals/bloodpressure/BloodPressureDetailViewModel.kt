package app.readylytics.health.feature.vitals.bloodpressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.model.domain.model.BloodPressureStatus
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.repository.BloodPressureRepository
import app.readylytics.health.core.model.domain.service.HealthMetricsService
import app.readylytics.health.core.ui.common.BloodPressureHistoryItem
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.bucketBy
import app.readylytics.health.core.ui.common.buildPeriodAverageSummary
import app.readylytics.health.core.ui.common.padToRange
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

data class BloodPressureDetailUiState(
    val latestSystolic: Int? = null,
    val latestDiastolic: Int? = null,
    val latestDate: LocalDate? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailySystolic: List<DailyDataPoint> = emptyList(),
    val dailyDiastolic: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
    val systolicPeriodSummary: PeriodAverageSummary? = null,
    val diastolicPeriodSummary: PeriodAverageSummary? = null,
    val bloodPressureDisplay: String? = null,
    val systolicStatus: MetricStatus = MetricStatus.CALIBRATING,
    val diastolicStatus: MetricStatus = MetricStatus.CALIBRATING,
    val bloodPressureStatus: BloodPressureStatus? = null,
    val historyItems: List<BloodPressureHistoryItem> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val isLoading: Boolean = true,
)

@HiltViewModel
class BloodPressureDetailViewModel
    @Inject
    constructor(
        private val bloodPressureRepository: BloodPressureRepository,
        private val selectedDateRepository: SelectedDateStore,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val selectedRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)
        private val currentPageFlow = MutableStateFlow(1)
        private val healthMetricsService = HealthMetricsService()

        private data class BpParams(
            val range: TimeRange,
            val date: LocalDate,
            val page: Int,
        )

        val uiState: StateFlow<BloodPressureDetailUiState> =
            combine(
                selectedRangeFlow,
                selectedDateRepository.selectedDate,
                currentPageFlow,
            ) { range, selectedDate, page -> BpParams(range, selectedDate, page) }
                .scan(null as BpParams?) { prev, current ->
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
                    initialValue = BloodPressureDetailUiState(),
                )

        private suspend fun buildState(params: BpParams): BloodPressureDetailUiState {
            val range = params.range
            val selectedDate = params.date
            val page = params.page
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

            val totalCount =
                bloodPressureRepository.countByDateRange(
                    rangeStart.toEpochMilli(),
                    rangeEnd.toEpochMilli(),
                )
            val totalPages = maxOf(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE)
            val clampedPage = page.coerceIn(1, totalPages)
            val offset = (clampedPage - 1) * PAGE_SIZE

            val pagedRecords =
                bloodPressureRepository.getByDateRangePaged(
                    rangeStart.toEpochMilli(),
                    rangeEnd.toEpochMilli(),
                    PAGE_SIZE,
                    offset,
                )

            val recordsByDay =
                records.groupBy { record ->
                    ChronoUnit.DAYS
                        .between(
                            rangeStart.atZone(zoneId).toLocalDate(),
                            record.time.atZone(zoneId).toLocalDate(),
                        ).toInt()
                }

            val startDate = rangeStart.atZone(zoneId).toLocalDate()
            val systolicRaw =
                recordsByDay
                    .map { (dayOffset, dayRecords) ->
                        // Allow-listed: chart-axis geometry for plotted BP series, not a display metric
                        val avgSystolic = dayRecords.map { it.systolicMmHg }.average().toFloat()
                        DailyDataPoint(dayOffset, avgSystolic)
                    }.sortedBy { it.dayOffset }

            val dailySystolic =
                if (range.granularity == TrendGranularity.DAILY) {
                    systolicRaw.padToRange(range.days)
                } else {
                    systolicRaw.bucketBy(range.granularity, startDate, selectedDate, valueDecimalPlaces = 0)
                }

            val diastolicRaw =
                recordsByDay
                    .map { (dayOffset, dayRecords) ->
                        // Allow-listed: chart-axis geometry for plotted BP series, not a display metric
                        val avgDiastolic = dayRecords.map { it.diastolicMmHg }.average().toFloat()
                        DailyDataPoint(dayOffset, avgDiastolic)
                    }.sortedBy { it.dayOffset }

            val dailyDiastolic =
                if (range.granularity == TrendGranularity.DAILY) {
                    diastolicRaw.padToRange(range.days)
                } else {
                    diastolicRaw.bucketBy(range.granularity, startDate, selectedDate, valueDecimalPlaces = 0)
                }
            val systolicPeriodSummary =
                if (range.granularity == TrendGranularity.DAILY) {
                    null
                } else {
                    buildPeriodAverageSummary(dailySystolic, range.granularity, startDate)
                }
            val diastolicPeriodSummary =
                if (range.granularity == TrendGranularity.DAILY) {
                    null
                } else {
                    buildPeriodAverageSummary(dailyDiastolic, range.granularity, startDate)
                }

            val latestSystolic = latest?.systolicMmHg
            val latestDiastolic = latest?.diastolicMmHg

            val systolicStatus = healthMetricsService.assessSystolic(latestSystolic)
            val diastolicStatus = healthMetricsService.assessDiastolic(latestDiastolic)
            val bloodPressureStatus =
                if (latestSystolic != null && latestDiastolic != null) {
                    healthMetricsService.assessBloodPressure(latestSystolic, latestDiastolic)
                } else {
                    null
                }

            val bloodPressureDisplay =
                if (latestSystolic != null && latestDiastolic != null) {
                    MetricFormatter.formatBloodPressure(latestSystolic, latestDiastolic)
                } else {
                    null
                }

            val historyItems =
                pagedRecords
                    .map { record ->
                        BloodPressureHistoryItem(
                            timestampMs = record.time.toEpochMilli(),
                            systolic = record.systolicMmHg,
                            diastolic = record.diastolicMmHg,
                            status =
                                healthMetricsService.assessBloodPressure(
                                    record.systolicMmHg,
                                    record.diastolicMmHg,
                                ),
                        )
                    }

            return BloodPressureDetailUiState(
                latestSystolic = latestSystolic,
                latestDiastolic = latestDiastolic,
                latestDate = latest?.time?.atZone(zoneId)?.toLocalDate(),
                selectedRange = range,
                dailySystolic = dailySystolic,
                dailyDiastolic = dailyDiastolic,
                rangeStartMs = rangeStart.toEpochMilli(),
                systolicPeriodSummary = systolicPeriodSummary,
                diastolicPeriodSummary = diastolicPeriodSummary,
                bloodPressureDisplay = bloodPressureDisplay,
                systolicStatus = systolicStatus,
                diastolicStatus = diastolicStatus,
                bloodPressureStatus = bloodPressureStatus,
                historyItems = historyItems,
                currentPage = clampedPage,
                totalPages = totalPages,
                isLoading = false,
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
