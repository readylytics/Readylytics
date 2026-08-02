package app.readylytics.health.feature.vitals.bloodpressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.BloodPressureHistoryItem
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.padToRange
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.repository.BloodPressureRepository
import app.readylytics.health.domain.service.HealthMetricsService
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

data class BloodPressureDetailUiState(
    val latestSystolic: Int? = null,
    val latestDiastolic: Int? = null,
    val latestDate: LocalDate? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val dailySystolic: List<DailyDataPoint> = emptyList(),
    val dailyDiastolic: List<DailyDataPoint> = emptyList(),
    val rangeStartMs: Long = 0,
    val bloodPressureDisplay: String? = null,
    val systolicStatus: MetricStatus = MetricStatus.CALIBRATING,
    val diastolicStatus: MetricStatus = MetricStatus.CALIBRATING,
    val bloodPressureStatus: BloodPressureStatus? = null,
    val historyItems: List<BloodPressureHistoryItem> = emptyList(),
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
        private val healthMetricsService = HealthMetricsService()

        val uiState: StateFlow<BloodPressureDetailUiState> =
            combine(
                selectedRangeFlow,
                selectedDateRepository.selectedDate,
            ) { range, selectedDate ->
                withContext(ioDispatcher) {
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

                    val recordsByDay =
                        records.groupBy { record ->
                            ChronoUnit.DAYS
                                .between(
                                    rangeStart.atZone(zoneId).toLocalDate(),
                                    record.time.atZone(zoneId).toLocalDate(),
                                ).toInt()
                        }

                    val dailySystolic =
                        recordsByDay
                            .map { (dayOffset, dayRecords) ->
                                // Allow-listed: chart-axis geometry for plotted BP series, not a display metric
                                val avgSystolic = dayRecords.map { it.systolicMmHg }.average().toFloat()
                                DailyDataPoint(dayOffset, avgSystolic)
                            }.sortedBy { it.dayOffset }
                            .padToRange(range.days)

                    val dailyDiastolic =
                        recordsByDay
                            .map { (dayOffset, dayRecords) ->
                                // Allow-listed: chart-axis geometry for plotted BP series, not a display metric
                                val avgDiastolic = dayRecords.map { it.diastolicMmHg }.average().toFloat()
                                DailyDataPoint(dayOffset, avgDiastolic)
                            }.sortedBy { it.dayOffset }
                            .padToRange(range.days)

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
                        records
                            .sortedByDescending { it.time }
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

                    BloodPressureDetailUiState(
                        latestSystolic = latestSystolic,
                        latestDiastolic = latestDiastolic,
                        latestDate = latest?.time?.atZone(zoneId)?.toLocalDate(),
                        selectedRange = range,
                        dailySystolic = dailySystolic,
                        dailyDiastolic = dailyDiastolic,
                        rangeStartMs = rangeStart.toEpochMilli(),
                        bloodPressureDisplay = bloodPressureDisplay,
                        systolicStatus = systolicStatus,
                        diastolicStatus = diastolicStatus,
                        bloodPressureStatus = bloodPressureStatus,
                        historyItems = historyItems,
                        isLoading = false,
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = BloodPressureDetailUiState(),
            )

        fun onRangeSelected(range: TimeRange) {
            selectedRangeFlow.value = range
        }
    }
