package app.readylytics.health.ui.vitals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ZoneBand
import app.readylytics.health.domain.model.hrvZoneBands
import app.readylytics.health.domain.model.rhrZoneBands
import app.readylytics.health.domain.model.spo2ZoneBands
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.scoring.HrvBaselineProvider
import app.readylytics.health.domain.scoring.RhrBaselineProvider
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.util.truncateToDayMs
import app.readylytics.health.ui.common.DailyDataPoint
import app.readylytics.health.ui.common.TimeRange
import app.readylytics.health.ui.common.padToRange
import app.readylytics.health.ui.sleep.Baselines
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
        private val dailyMetricsRepository: DailyMetricsRepository,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
        private val foregroundSyncController: ForegroundSyncController,
        private val savedStateHandle: SavedStateHandle,
        private val hrvBaselineProvider: HrvBaselineProvider,
        private val rhrBaselineProvider: RhrBaselineProvider,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        val baselinesFlow =
            selectedDateRepository.selectedDate
                .map { date ->
                    Baselines(
                        hrv = hrvBaselineProvider.getRoundedHrvBaseline(date)?.toFloat(),
                        rhr = rhrBaselineProvider.getRoundedRhrBaseline(date),
                    )
                }.flowOn(Dispatchers.IO)
                .stateIn(
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
                                    s.restingHeartRate?.let { rhr ->
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
                                            // Allow-listed: chart-axis geometry for the plotted SpO2 series,
                                            // not a displayed metric value (which comes from DailyMetrics.spo2Rounded).
                                            value = spo2.roundToInt().toFloat(),
                                        )
                                    }
                                }.sortedBy { it.dayOffset }
                                .padToRange(range.days)

                        val baselineHrv = bHrv
                        val baselineRhr = bRhr?.toFloat()

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
                    initialValue = VitalsUiState(isLoading = true),
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

        val earliestDate: StateFlow<LocalDate?> =
            selectedDateRepository.earliestDate
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        fun onDateSelected(date: LocalDate) {
            viewModelScope.launch {
                selectedDateRepository.updateSelectedDate(date)
            }
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
