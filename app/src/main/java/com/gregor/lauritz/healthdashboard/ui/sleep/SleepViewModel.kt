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
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class TimeRange(
    val days: Int,
    val label: String,
) {
    SEVEN_DAYS(7, "7D"),
    THIRTY_DAYS(30, "30D"),
    SIX_MONTHS(180, "180D"),
    ;

    fun fromMs(): Long = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
}

data class SleepUiState(
    val latestSummary: DailySummaryEntity? = null,
    val latestSession: SleepSessionEntity? = null,
    val dailyHrv: List<Float> = emptyList(),
    val dailyRhr: List<Float> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val goalSleepMinutes: Int = 480,
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

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            _selectedRange
                .flatMapLatest { range ->
                    val fromMs = range.fromMs()
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
                            dailyHrv = groupHrvByDay(hrvRecords),
                            dailyRhr = groupRhrByDay(hrRecords),
                            goalSleepMinutes = (prefs.goalSleepHours * 60).toInt(),
                            selectedRange = range,
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

private fun groupHrvByDay(records: List<HrvRecordEntity>): List<Float> =
    records
        .groupBy { truncateToDayMs(it.timestampMs) }
        .toSortedMap()
        .values
        .map { daily -> daily.map { it.rmssdMs }.average().toFloat() }

private fun groupRhrByDay(records: List<HeartRateRecordEntity>): List<Float> =
    records
        .groupBy { truncateToDayMs(it.timestampMs) }
        .toSortedMap()
        .values
        .map { daily -> daily.minOf { it.beatsPerMinute }.toFloat() }

private fun truncateToDayMs(timestampMs: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestampMs
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
