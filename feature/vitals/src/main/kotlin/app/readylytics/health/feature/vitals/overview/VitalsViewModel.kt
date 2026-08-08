package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.model.Baselines
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.scoring.HrvBaselineProvider
import app.readylytics.health.domain.scoring.RhrBaselineProvider
import app.readylytics.health.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@Immutable
data class VitalsUiState(
    val latestSummary: DailySummary? = null,
    val chartSeries: VitalsChartSeries = VitalsChartSeries(emptyList(), emptyList(), emptyList(), emptyList()),
    val presentation: VitalsPresentationState = VitalsPresentationState.empty(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
)

private data class VitalsSelection(
    val range: TimeRange,
    val date: LocalDate,
)

private data class VitalsContentState(
    val latestSummary: DailySummary?,
    val chartSeries: VitalsChartSeries,
    val selection: VitalsSelection,
    val rangeStartMs: Long,
)

@HiltViewModel
class VitalsViewModel
    @Inject
    constructor(
        private val dailySummaryRepository: DailySummaryRepository,
        private val dailyMetricsRepository: DailyMetricsRepository,
        private val settingsRepo: UserPreferencesReader,
        private val selectedDateRepository: SelectedDateStore,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val savedStateHandle: SavedStateHandle,
        private val hrvBaselineProvider: HrvBaselineProvider,
        private val rhrBaselineProvider: RhrBaselineProvider,
        private val bodyTemperatureBaselineProvider: BodyTemperatureBaselineProvider,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        private val scoringBaselinesFlow =
            selectedDateRepository.selectedDate
                .map { date ->
                    Baselines(
                        hrv = hrvBaselineProvider.getRoundedHrvBaseline(date)?.toFloat(),
                        rhr = rhrBaselineProvider.getRoundedRhrBaseline(date),
                    )
                }

        @OptIn(ExperimentalCoroutinesApi::class)
        private val bodyTemperatureBaselineFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date -> bodyTemperatureBaselineProvider.observeBaseline(date) }

        private val baselinesFlow =
            combine(scoringBaselinesFlow, bodyTemperatureBaselineFlow) { scoring, bodyTemp ->
                scoring.copy(bodyTemp = bodyTemp)
            }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        private val selectionFlow =
            combine(_selectedRange, selectedDateRepository.selectedDate, ::VitalsSelection)
                .distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        private val contentFlow =
            combine(selectionFlow, settingsRepo.userPreferences) { selection, prefs -> selection to prefs }
                .flatMapLatest { selection ->
                    val (vitalsSelection, prefs) = selection
                    val window =
                        resolveVitalsRangeWindow(
                            range = vitalsSelection.range,
                            selectedDate = vitalsSelection.date,
                            scoringZone = prefs.scoringZone(),
                        )
                    val latestFlow =
                        if (window.isToday) {
                            dailySummaryRepository.observeSince(window.selectedMidnightMs).map { it.firstOrNull() }
                        } else {
                            dailySummaryRepository.observeByDate(window.selectedMidnightMs)
                        }

                    combine(
                        latestFlow,
                        dailySummaryRepository.observeSince(window.fromMs),
                    ) { latest, summaries ->
                        VitalsContentState(
                            latestSummary = latest,
                            chartSeries =
                                buildVitalsChartSeries(
                                    summaries,
                                    window.startDate,
                                    vitalsSelection.range.days,
                                    prefs.unitSystem,
                                    endDate = vitalsSelection.date,
                                ),
                            selection = vitalsSelection,
                            rangeStartMs = window.fromMs,
                        )
                    }.distinctUntilChanged()
                }.flowOn(ioDispatcher)

        private val presentationFlow =
            combine(settingsRepo.userPreferences, baselinesFlow) { prefs, baselines ->
                buildVitalsPresentationState(
                    baselines = baselines,
                    hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                    hrvWarningThreshold = prefs.hrvWarningThreshold,
                    rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                    rhrWarningThreshold = prefs.rhrWarningThreshold,
                    unitSystem = prefs.unitSystem,
                )
            }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        val uiState: StateFlow<VitalsUiState> =
            // isLoading now means "true first-load, no data yet" (skeleton). isRefreshing tracks
            // every sync regardless of data presence, and only gates the date-switcher (see
            // VitalsScreen). Mirrors DashboardViewModel's isComputingMetrics/isRefreshing split.
            // The "no data yet" signal is based on whether the trend charts have any real
            // historical point loaded, not on whether the *selected day's* summary exists --
            // latestSummary is scoped to the selected date, so on the first sync of a new day
            // (before today's summary is computed) it is null even though 7-90 days of unchanged
            // chart history are already loaded. Checking chart history instead avoids flashing the
            // skeleton and tearing down/rebuilding the Vico charts once per day.
            combine(
                contentFlow,
                presentationFlow,
                foregroundSyncController.isSyncing,
            ) { content, presentation, isSyncing ->
                val hasHistoricalData =
                    content.chartSeries.hrv.any { it.value != null } ||
                        content.chartSeries.rhr.any { it.value != null } ||
                        content.chartSeries.spo2.any { it.value != null } ||
                        content.chartSeries.bodyTemp.any { it.value != null }
                VitalsUiState(
                    latestSummary = content.latestSummary,
                    chartSeries = content.chartSeries,
                    presentation = presentation,
                    selectedRange = content.selection.range,
                    selectedDate = content.selection.date,
                    rangeStartMs = content.rangeStartMs,
                    isLoading = isSyncing && !hasHistoricalData,
                    isRefreshing = isSyncing,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = VitalsUiState(isLoading = true),
            )

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
