package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class SleepUiState(
    val latestSummary: DailySummaryEntity? = null,
    val latestSession: SleepSessionEntity? = null,
    val dailyHrv: List<DailyDataPoint> = emptyList(),
    val dailyRhr: List<DailyDataPoint> = emptyList(),
    val hrvBaseline: Float? = null,
    val rhrBaseline: Float? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val goalSleepMinutes: Int = 480,
    val rangeStartMs: Long = System.currentTimeMillis(),
)

fun SleepSessionEntity.efficiencyStatus(): MetricStatus =
    when {
        efficiency >= 85f -> MetricStatus.OPTIMAL
        efficiency >= 80f -> MetricStatus.NEUTRAL
        efficiency >= 70f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummaryEntity.deepSleepStatus(): MetricStatus =
    when (deepSleepPercent) {
        null -> MetricStatus.CALIBRATING
        in 25f..30f -> MetricStatus.NEUTRAL
        in 15f..25f -> MetricStatus.OPTIMAL
        in 10f..15f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummaryEntity.remSleepStatus(): MetricStatus =
    when (remSleepPercent) {
        null -> MetricStatus.CALIBRATING
        in 25f..30f -> MetricStatus.NEUTRAL
        in 20f..25f -> MetricStatus.OPTIMAL
        in 15f..20f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

@HiltViewModel
class SleepViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val sleepSessionDao: SleepSessionDao,
        private val hrvDao: HrvDao,
        private val heartRateDao: HeartRateDao,
        private val prefsRepo: UserPreferencesRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val circadianRepo: CircadianConsistencyRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)

        /**
         * Baseline HRV (calculated from past 30 days, constant across all views).
         * This ensures the baseline shown in charts matches the dashboard baseline.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val baselineHrvFlow =
            selectedDateRepository.selectedDate.flatMapLatest { date ->
                hrvDao
                    .observeSleepHrvSince(TimeRange.THIRTY_DAYS.fromMs(date))
                    .map { records ->
                        if (records.isEmpty()) {
                            null
                        } else {
                            val values =
                                records
                                    .map { it.rmssdMs }
                                    .sorted()
                            val mid = values.size / 2
                            if (values.size % 2 == 0) {
                                (values[mid - 1] + values[mid]) / 2f
                            } else {
                                values[mid]
                            }
                        }
                    }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

        /**
         * Baseline RHR (calculated from past 30 days, constant across all views).
         * This ensures the baseline shown in charts matches the dashboard baseline.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val baselineRhrFlow =
            selectedDateRepository.selectedDate.flatMapLatest { date ->
                heartRateDao
                    .observeSleepHrSince(TimeRange.THIRTY_DAYS.fromMs(date))
                    .map { records ->
                        if (records.isEmpty()) {
                            null
                        } else {
                            val sessionAvgs =
                                records
                                    .groupBy { it.sessionId }
                                    .values
                                    .map { sess ->
                                        sess
                                            .map { it.beatsPerMinute }
                                            .average()
                                            .toFloat()
                                    }
                            val sorted = sessionAvgs.sorted()
                            val mid = sorted.size / 2
                            val median =
                                if (sorted.size % 2 == 0) {
                                    (sorted[mid - 1] + sorted[mid]) / 2f
                                } else {
                                    sorted[mid]
                                }
                            median.toInt()
                        }
                    }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

        @OptIn(ExperimentalCoroutinesApi::class)
        val circadianConsistencyFlow =
            selectedDateRepository.selectedDate.flatMapLatest { date ->
                circadianRepo.resultFor(date)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CircadianConsistencyResult.Calibrating,
            )

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
            ) { range, date -> range to date }
                .flatMapLatest { (range, date) ->
                    val fromMs = range.fromMs(date)
                    val startDayMs = truncateToDayMs(fromMs)
                    val zoneId = ZoneId.systemDefault()
                    val selectedMidnightMs =
                        date
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    val nextDayMidnightMs =
                        date
                            .plusDays(1)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()

                    val summaryFlow =
                        if (date == LocalDate.now(zoneId)) {
                            dailySummaryDao.observeLatest()
                        } else {
                            flow { emit(dailySummaryDao.getByDate(selectedMidnightMs)) }
                        }

                    combine(
                        summaryFlow,
                        sleepSessionDao.observeFirstSessionEndingInRange(
                            selectedMidnightMs,
                            nextDayMidnightMs,
                        ),
                        hrvDao.observeSleepHrvSince(fromMs),
                        heartRateDao.observeSleepHrSince(fromMs),
                        prefsRepo.userPreferences,
                        baselineHrvFlow,
                        baselineRhrFlow,
                    ) { flows ->
                        val latestSummary = flows[0] as DailySummaryEntity?
                        val latestSession = flows[1] as SleepSessionEntity?
                        @Suppress("UNCHECKED_CAST")
                        val hrvRecords = flows[2] as List<HrvRecordEntity>
                        @Suppress("UNCHECKED_CAST")
                        val hrRecords = flows[3] as List<HeartRateRecordEntity>
                        val prefs = flows[4] as com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
                        val bHrv = flows[5] as Float?
                        val bRhr = flows[6] as Int?

                        val filteredHrv = hrvRecords.filter { it.timestampMs < nextDayMidnightMs }
                        val filteredHr = hrRecords.filter { it.timestampMs < nextDayMidnightMs }

                        val hrvPoints = groupHrvByDay(filteredHrv, startDayMs)
                        val rhrPoints = groupRhrByDay(filteredHr, startDayMs)

                        SleepUiState(
                            latestSummary = latestSummary,
                            latestSession = latestSession,
                            dailyHrv = hrvPoints,
                            dailyRhr = rhrPoints,
                            hrvBaseline = bHrv ?: calculateMedian(hrvPoints),
                            rhrBaseline = bRhr?.toFloat() ?: calculateMedian(rhrPoints),
                            goalSleepMinutes = (prefs.goalSleepHours * 60).toInt(),
                            selectedRange = range,
                            selectedDate = date,
                            rangeStartMs = startDayMs,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SleepUiState(),
                )

        private fun calculateMedian(points: List<DailyDataPoint>): Float? {
            if (points.isEmpty()) return null
            val sorted = points.map { it.value }.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }

        fun onPreviousDay() {
            selectedDateRepository.selectPreviousDay()
        }

        fun onNextDay() {
            selectedDateRepository.selectNextDay()
        }
    }

private fun groupHrvByDay(
    records: List<HrvRecordEntity>,
    startDayMs: Long,
): List<DailyDataPoint> {
    val zoneId = ZoneId.systemDefault()
    val startLocalDate = Instant.ofEpochMilli(startDayMs).atZone(zoneId).toLocalDate()
    return records
        .groupBy { truncateToDayMs(it.timestampMs) }
        .toSortedMap()
        .map { (dayMs_, daily) ->
            val currentLocalDate = Instant.ofEpochMilli(dayMs_).atZone(zoneId).toLocalDate()
            DailyDataPoint(
                dayOffset = ChronoUnit.DAYS.between(startLocalDate, currentLocalDate).toInt(),
                value =
                    daily
                        .map { it.rmssdMs }
                        .average()
                        .toFloat(),
            )
        }
}

private fun groupRhrByDay(
    records: List<HeartRateRecordEntity>,
    startDayMs: Long,
): List<DailyDataPoint> {
    val zoneId = ZoneId.systemDefault()
    val startLocalDate = Instant.ofEpochMilli(startDayMs).atZone(zoneId).toLocalDate()
    return records
        .groupBy { truncateToDayMs(it.timestampMs) }
        .toSortedMap()
        .map { (dayMs_, daily) ->
            val currentLocalDate = Instant.ofEpochMilli(dayMs_).atZone(zoneId).toLocalDate()
            DailyDataPoint(
                dayOffset = ChronoUnit.DAYS.between(startLocalDate, currentLocalDate).toInt(),
                value =
                    daily
                        .map { it.beatsPerMinute }
                        .average()
                        .toFloat(),
            )
        }
}

internal fun truncateToDayMs(timestampMs: Long): Long {
    val zoneId = ZoneId.systemDefault()
    return Instant.ofEpochMilli(timestampMs)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
}
