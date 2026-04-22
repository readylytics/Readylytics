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
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SleepUiState(
    val latestSummary: DailySummaryEntity? = null,
    val latestSession: SleepSessionEntity? = null,
    val dailyHrv: List<DailyDataPoint> = emptyList(),
    val dailyRhr: List<DailyDataPoint> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val goalSleepMinutes: Int = 480,
    val rangeStartMs: Long = System.currentTimeMillis(),
)

fun SleepSessionEntity.efficiencyStatus(): MetricStatus =
    when {
        efficiency >= 85f -> MetricStatus.OPTIMAL
        efficiency >= 70f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummaryEntity.deepSleepStatus(): MetricStatus =
    when {
        deepSleepPercent == null -> MetricStatus.CALIBRATING
        deepSleepPercent >= 20f -> MetricStatus.OPTIMAL
        deepSleepPercent >= 10f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummaryEntity.remSleepStatus(): MetricStatus =
    when {
        remSleepPercent == null -> MetricStatus.CALIBRATING
        remSleepPercent >= 20f -> MetricStatus.OPTIMAL
        remSleepPercent >= 15f -> MetricStatus.WARNING
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
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)
        val selectedRange = _selectedRange

        /**
         * Baseline HRV (calculated from past 30 days, constant across all views).
         * This ensures the baseline shown in charts matches the dashboard baseline.
         */
        val baselineHrvFlow =
            hrvDao
                .observeSleepHrvSince(TimeRange.THIRTY_DAYS.fromMs())
                .map { records ->
                    if (records.isEmpty()) {
                        null
                    } else {
                        val values = records.map { it.rmssdMs }.sorted()
                        val mid = values.size / 2
                        if (values.size % 2 == 0) (values[mid - 1] + values[mid]) / 2f else values[mid]
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
        val baselineRhrFlow =
            heartRateDao
                .observeSleepHrSince(TimeRange.THIRTY_DAYS.fromMs())
                .map { records ->
                    if (records.isEmpty()) {
                        null
                    } else {
                        val sessionAvgs =
                            records
                                .groupBy { it.sessionId }
                                .values
                                .map { sess -> sess.map { it.beatsPerMinute }.average().toFloat() }
                        val sorted = sessionAvgs.sorted()
                        val mid = sorted.size / 2
                        val median =
                            if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
                        median.toInt()
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            _selectedRange
                .flatMapLatest { range ->
                    val fromMs = range.fromMs()
                    val startDayMs = truncateToDayMs(fromMs)
                    combine(
                        dailySummaryDao.observeLatest(),
                        sleepSessionDao.observeLatest(),
                        hrvDao.observeSleepHrvSince(fromMs),
                        heartRateDao.observeSleepHrSince(fromMs),
                        prefsRepo.userPreferences,
                    ) { latestSummary, latestSession, hrvRecords, hrRecords, prefs ->
                        SleepUiState(
                            latestSummary = latestSummary,
                            latestSession = latestSession,
                            dailyHrv = groupHrvByDay(hrvRecords, startDayMs),
                            dailyRhr = groupRhrByDay(hrRecords, startDayMs),
                            goalSleepMinutes = (prefs.goalSleepHours * 60).toInt(),
                            selectedRange = range,
                            rangeStartMs = startDayMs,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = SleepUiState(),
                )

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
        }
    }

private fun groupHrvByDay(
    records: List<HrvRecordEntity>,
    startDayMs: Long,
): List<DailyDataPoint> {
    val dayMs = TimeUnit.DAYS.toMillis(1)
    return records
        .groupBy { truncateToDayMs(it.timestampMs) }
        .toSortedMap()
        .map { (dayMs_, daily) ->
            DailyDataPoint(
                dayOffset = ((dayMs_ - startDayMs) / dayMs).toInt(),
                value = daily.map { it.rmssdMs }.average().toFloat(),
            )
        }
}

private fun groupRhrByDay(
    records: List<HeartRateRecordEntity>,
    startDayMs: Long,
): List<DailyDataPoint> {
    val dayMs = TimeUnit.DAYS.toMillis(1)
    return records
        .groupBy { truncateToDayMs(it.timestampMs) }
        .toSortedMap()
        .map { (dayMs_, daily) ->
            DailyDataPoint(
                dayOffset = ((dayMs_ - startDayMs) / dayMs).toInt(),
                value = daily.map { it.beatsPerMinute }.average().toFloat(),
            )
        }
}

internal fun truncateToDayMs(timestampMs: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestampMs
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
