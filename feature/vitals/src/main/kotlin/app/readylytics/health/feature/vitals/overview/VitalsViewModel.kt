package app.readylytics.health.feature.vitals.overview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.model.Baselines
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.scoring.HrvBaselineProvider
import app.readylytics.health.domain.scoring.RhrBaselineProvider
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import app.readylytics.health.domain.util.truncateToDayMs
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class VitalsUiState(
    val latestSummary: DailySummary? = null,
    val chartSeries: VitalsChartSeries = VitalsChartSeries(emptyList(), emptyList(), emptyList()),
    val presentation: VitalsPresentationState = VitalsPresentationState.empty(),
    val selectedRange: TimeRange = TimeRange.SEVEN_DAYS,
    val selectedDate: LocalDate = LocalDate.now(),
    val rangeStartMs: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false,
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
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        private val baselinesFlow =
            selectedDateRepository.selectedDate
                .map { date ->
                    Baselines(
                        hrv = hrvBaselineProvider.getRoundedHrvBaseline(date)?.toFloat(),
                        rhr = rhrBaselineProvider.getRoundedRhrBaseline(date),
                    )
                }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        private val selectionFlow =
            combine(_selectedRange, selectedDateRepository.selectedDate, ::VitalsSelection)
                .distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        private val contentFlow =
            selectionFlow
                .flatMapLatest { selection ->
                    val fromMs = selection.range.fromMs(selection.date)
                    val startDayMs = fromMs.truncateToDayMs()
                    val zoneId = ZoneId.systemDefault()
                    val startDate = Instant.ofEpochMilli(startDayMs).atZone(zoneId).toLocalDate()
                    val selectedMidnightMs =
                        selection
                            .date
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli()
                    val latestFlow =
                        if (selection.date == LocalDate.now(zoneId)) {
                            val todayMs =
                                LocalDate
                                    .now(zoneId)
                                    .atStartOfDay(zoneId)
                                    .toInstant()
                                    .toEpochMilli()
                            dailySummaryRepository.observeSince(todayMs).map { it.firstOrNull() }
                        } else {
                            dailySummaryRepository.observeByDate(selectedMidnightMs)
                        }

                    combine(
                        latestFlow,
                        dailySummaryRepository.observeSince(fromMs),
                    ) { latest, summaries ->
                        VitalsContentState(
                            latestSummary = latest,
                            chartSeries = buildVitalsChartSeries(summaries, startDate, selection.range.days),
                            selection = selection,
                            rangeStartMs = startDayMs,
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
                )
            }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        val uiState: StateFlow<VitalsUiState> =
            combine(
                contentFlow,
                presentationFlow,
                foregroundSyncController.isSyncing,
            ) { content, presentation, isSyncing ->
                VitalsUiState(
                    latestSummary = content.latestSummary,
                    chartSeries = content.chartSeries,
                    presentation = presentation,
                    selectedRange = content.selection.range,
                    selectedDate = content.selection.date,
                    rangeStartMs = content.rangeStartMs,
                    isLoading = isSyncing,
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
