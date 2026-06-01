package com.gregor.lauritz.healthdashboard.ui.vitals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.domain.model.hrvZoneBands
import com.gregor.lauritz.healthdashboard.domain.model.rhrZoneBands
import com.gregor.lauritz.healthdashboard.domain.model.spo2ZoneBands
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import com.gregor.lauritz.healthdashboard.domain.util.truncateToDayMs
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.common.padToRange
import com.gregor.lauritz.healthdashboard.ui.sleep.Baselines
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToInt

data class VitalsUiState(
    val latestSummary: DailySummary? = null,
    val dailyHrv: List<DailyDataPoint> = emptyList(),
    val dailyRhr: List<DailyDataPoint> = emptyList(),
    val dailySpo2: List<DailyDataPoint> = emptyList(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val hrvZoneBands: List<ZoneBand>? = null,
    val rhrZoneBands: List<ZoneBand>? = null,
    val spo2ZoneBands: List<ZoneBand>? = null,
    val hrvOptimalThreshold: Float = 0.9f,
    val hrvWarningThreshold: Float = 0.8f,
    val rhrOptimalThreshold: Float = 1.05f,
    val rhrWarningThreshold: Float = 1.15f,
)

@HiltViewModel
class VitalsViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val foregroundSyncController: ForegroundSyncController,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val baselinesFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    val selectedMidnightMs = date.toMidnightEpochMilli()
                    combine(
                        dailySummaryRepository.observeByDate(selectedMidnightMs),
                        settingsRepo.userPreferences,
                    ) { latestSummary, prefs ->
                        val hrvBaseline =
                            latestSummary?.hrvBaseline?.toFloat()
                                ?: prefs.hrvBaselineOverride

                        val ratio = latestSummary?.rhrRatio
                        val rhr = latestSummary?.nocturnalRhr
                        val rhrBaseline =
                            if (ratio != null && ratio > 0f && rhr != null) {
                                (rhr / ratio).toInt()
                            } else {
                                latestSummary?.restingHrBaseline ?: prefs.rhrBaselineOverride?.toInt()
                                    ?: ScoringConstants.DEFAULT_RHR_BPM.toInt()
                            }

                        Baselines(hrvBaseline, rhrBaseline)
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = Baselines(),
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

                    val summaryFlow =
                        if (date == LocalDate.now(zoneId)) {
                            val todayMs =
                                LocalDate
                                    .now(zoneId)
                                    .atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli()
                            dailySummaryRepository
                                .observeSince(todayMs)
                                .map { it.firstOrNull() }
                        } else {
                            dailySummaryRepository.observeByDate(selectedMidnightMs)
                        }

                    combine(
                        summaryFlow,
                        dailySummaryRepository.observeSince(fromMs),
                        settingsRepo.userPreferences,
                        baselinesFlow,
                        foregroundSyncController.isSyncing,
                    ) { latestSummary, summaries, prefs, baselines, isSyncing ->
                        val (bHrv, bRhr) = baselines
                        val startLocalDate = Instant.ofEpochMilli(startDayMs).atZone(zoneId).toLocalDate()

                        val hrvPoints =
                            summaries
                                .mapNotNull { s ->
                                    s.nocturnalHrv?.let { hrv ->
                                        val d = s.date
                                        DailyDataPoint(
                                            dayOffset = ChronoUnit.DAYS.between(startLocalDate, d).toInt(),
                                            value = hrv.toFloat(),
                                        )
                                    }
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        val rhrPoints =
                            summaries
                                .mapNotNull { s ->
                                    s.nocturnalRhr?.let { rhr ->
                                        val d = s.date
                                        DailyDataPoint(
                                            dayOffset = ChronoUnit.DAYS.between(startLocalDate, d).toInt(),
                                            value = rhr.toFloat(),
                                        )
                                    }
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        val spo2Points =
                            summaries
                                .mapNotNull { s ->
                                    s.avgSleepingSpo2?.let { spo2 ->
                                        val d = s.date
                                        DailyDataPoint(
                                            dayOffset = ChronoUnit.DAYS.between(startLocalDate, d).toInt(),
                                            value = spo2.roundToInt().toFloat(),
                                        )
                                    }
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        val baselineHrv = bHrv ?: calculateMedian(hrvPoints)
                        val baselineRhr = bRhr?.toFloat() ?: calculateMedian(rhrPoints)

                        val hrvBands =
                            baselineHrv?.let { baseline ->
                                hrvZoneBands(
                                    optimalMin = prefs.hrvOptimalThreshold * baseline,
                                    neutralMin = prefs.hrvWarningThreshold * baseline,
                                    warningMin = (2f * prefs.hrvWarningThreshold - 1f) * baseline,
                                )
                            }

                        val rhrBands =
                            baselineRhr?.let { baseline ->
                                rhrZoneBands(
                                    optimalMax = prefs.rhrOptimalThreshold * baseline,
                                    neutralMax = prefs.rhrWarningThreshold * baseline,
                                    warningMax = prefs.rhrWarningThreshold * 1.3f * baseline,
                                )
                            }

                        val spo2Bands = spo2ZoneBands()

                        VitalsUiState(
                            latestSummary = latestSummary,
                            dailyHrv = hrvPoints,
                            dailyRhr = rhrPoints,
                            dailySpo2 = spo2Points,
                            selectedRange = range,
                            selectedDate = date,
                            rangeStartMs = startDayMs,
                            isLoading = isSyncing,
                            hrvZoneBands = hrvBands,
                            rhrZoneBands = rhrBands,
                            spo2ZoneBands = spo2Bands,
                            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                            hrvWarningThreshold = prefs.hrvWarningThreshold,
                            rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                            rhrWarningThreshold = prefs.rhrWarningThreshold,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = VitalsUiState(),
                )

        private fun calculateMedian(points: List<DailyDataPoint>): Float? {
            val values = points.mapNotNull { it.value }
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
        }

        fun onRangeSelected(range: TimeRange) {
            _selectedRange.value = range
            savedStateHandle["selectedRange"] = range
        }

        fun onPreviousDay() {
            viewModelScope.launch {
                selectedDateRepository.selectPreviousDay()
            }
        }

        fun onNextDay() {
            viewModelScope.launch {
                selectedDateRepository.selectNextDay()
            }
        }
    }
