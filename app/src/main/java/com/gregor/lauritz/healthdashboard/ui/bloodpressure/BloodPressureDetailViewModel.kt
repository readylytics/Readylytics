package com.gregor.lauritz.healthdashboard.ui.bloodpressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.display.MetricFormatter
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.repository.BloodPressureRepository
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
    val statusLabel: String? = null,
)

@HiltViewModel
class BloodPressureDetailViewModel
    @Inject
    constructor(
        private val bloodPressureRepository: BloodPressureRepository,
        private val selectedDateRepository: SelectedDateRepository,
    ) : ViewModel() {
        private val selectedRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

        val uiState: StateFlow<BloodPressureDetailUiState> =
            combine(
                selectedRangeFlow,
                selectedDateRepository.selectedDate,
            ) { range, selectedDate ->
                withContext(Dispatchers.IO) {
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
                                    Instant.ofEpochMilli(record.timestampMs).atZone(zoneId).toLocalDate(),
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

                    val systolicStatus =
                        when (latestSystolic) {
                            null -> MetricStatus.CALIBRATING
                            in 0..119 -> MetricStatus.OPTIMAL
                            in 120..129 -> MetricStatus.NEUTRAL
                            in 130..139 -> MetricStatus.WARNING
                            else -> MetricStatus.POOR
                        }

                    val diastolicStatus =
                        when (latestDiastolic) {
                            null -> MetricStatus.CALIBRATING
                            in 0..79 -> MetricStatus.OPTIMAL
                            in 80..89 -> MetricStatus.WARNING
                            else -> MetricStatus.POOR
                        }

                    val statusLabel =
                        when {
                            latestSystolic == null || latestDiastolic == null -> null
                            latestSystolic in 0..119 && latestDiastolic in 0..79 -> "Normal"
                            latestSystolic in 120..129 && latestDiastolic in 0..79 -> "Elevated"
                            latestSystolic >= 130 || latestDiastolic >= 80 -> "High"
                            else -> null
                        }

                    val bloodPressureDisplay =
                        if (latestSystolic != null && latestDiastolic != null) {
                            MetricFormatter.formatBloodPressure(latestSystolic, latestDiastolic)
                        } else {
                            null
                        }

                    BloodPressureDetailUiState(
                        latestSystolic = latestSystolic,
                        latestDiastolic = latestDiastolic,
                        latestDate = latest?.timestampMs?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() },
                        selectedRange = range,
                        dailySystolic = dailySystolic,
                        dailyDiastolic = dailyDiastolic,
                        rangeStartMs = rangeStart.toEpochMilli(),
                        bloodPressureDisplay = bloodPressureDisplay,
                        systolicStatus = systolicStatus,
                        diastolicStatus = diastolicStatus,
                        statusLabel = statusLabel,
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
