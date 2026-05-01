package com.gregor.lauritz.healthdashboard.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.CardConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardManagementDelegate
import com.gregor.lauritz.healthdashboard.domain.dashboard.ScreenType
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val latestSummary: DailySummary? = null,
    val latestSession: SleepSessionEntity? = null,
    val dailyHrv: List<DailyDataPoint> = emptyList(),
    val dailyRhr: List<DailyDataPoint> = emptyList(),
    val hrvBaseline: Float? = null,
    val rhrBaseline: Float? = null,
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val goalSleepMinutes: Int = 480,
    val rangeStartMs: Long = System.currentTimeMillis(),
    val cardConfigurations: List<CardConfiguration> = emptyList(),
    val isManagingCards: Boolean = false,
)

fun SleepSessionEntity.efficiencyStatus(): MetricStatus =
    when {
        efficiency >= 85f -> MetricStatus.OPTIMAL
        efficiency >= 80f -> MetricStatus.NEUTRAL
        efficiency >= 70f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummary.deepSleepStatus(): MetricStatus =
    when (deepSleepPercent) {
        null -> MetricStatus.CALIBRATING
        in 25f..30f -> MetricStatus.NEUTRAL
        in 15f..25f -> MetricStatus.OPTIMAL
        in 10f..15f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummary.remSleepStatus(): MetricStatus =
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
        private val cardConfigRepository: CardConfigurationRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val circadianRepo: CircadianConsistencyRepository,
    ) : ViewModel() {
        private val _selectedRange = MutableStateFlow(TimeRange.SEVEN_DAYS)

        private val cardManagementDelegate = CardManagementDelegate(cardConfigRepository, viewModelScope)

        val isManagingCards: StateFlow<Boolean> = cardManagementDelegate.isManagingCards

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
                    val startDayMs = fromMs.truncateToDayMs()
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
                            val todayMs = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
                            dailySummaryDao.observeSince(todayMs)
                                .map { it.firstOrNull()?.let { DailySummaryMapper.toDomain(it) } }
                        } else {
                            flow { emit(dailySummaryDao.getByDate(selectedMidnightMs)?.let { DailySummaryMapper.toDomain(it) }) }
                        }

                    combine(
                        summaryFlow,
                        sleepSessionDao.observeFirstSessionEndingInRange(
                            selectedMidnightMs,
                            nextDayMidnightMs,
                        ),
                        dailySummaryDao.observeSince(fromMs),
                        prefsRepo.userPreferences,
                        baselineHrvFlow,
                        baselineRhrFlow,
                        cardManagementDelegate.isManagingCards,
                        cardConfigRepository.sleepCardConfigurations(),
                    ) { flows ->
                        val latestSummary = flows[0] as DailySummary?
                        val latestSession = flows[1] as SleepSessionEntity?
                        @Suppress("UNCHECKED_CAST")
                        val summaries = (flows[2] as List<DailySummaryEntity>).map { DailySummaryMapper.toDomain(it) }
                        val prefs = flows[3] as com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
                        val bHrv = flows[4] as Float?
                        val bRhr = flows[5] as Int?
                        val isManaging = flows[6] as Boolean
                        @Suppress("UNCHECKED_CAST")
                        val cardConfigs = flows[7] as List<CardConfiguration>

                        val startLocalDate = Instant.ofEpochMilli(startDayMs).atZone(zoneId).toLocalDate()

                        val hrvPoints = summaries.mapNotNull { s ->
                            s.nocturnalHrv?.let { hrv ->
                                val d = s.date
                                DailyDataPoint(dayOffset = ChronoUnit.DAYS.between(startLocalDate, d).toInt(), value = hrv.toFloat())
                            }
                        }
                        val rhrPoints = summaries.mapNotNull { s ->
                            s.nocturnalRhr?.let { rhr ->
                                val d = s.date
                                DailyDataPoint(dayOffset = ChronoUnit.DAYS.between(startLocalDate, d).toInt(), value = rhr.toFloat())
                            }
                        }

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
                            cardConfigurations = cardConfigs,
                            isManagingCards = isManaging,
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

        fun toggleCardManagement() {
            cardManagementDelegate.toggleCardManagement()
        }

        fun onToggleCardVisibility(cardId: CardId) {
            cardManagementDelegate.onToggleCardVisibility(
                ScreenType.SLEEP,
                uiState.value.cardConfigurations,
                cardId,
            )
        }

        fun onReorderCards(newOrder: List<CardConfiguration>) {
            cardManagementDelegate.onReorderCards(
                ScreenType.SLEEP,
                uiState.value.cardConfigurations,
                newOrder,
            )
        }

        fun onResetToDefaults() {
            cardManagementDelegate.onResetToDefaults(ScreenType.SLEEP)
        }
    }

