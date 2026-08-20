package app.readylytics.health.feature.vitals.overview

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.dashboard.CardManagementEvent
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.layout.LayoutManagementDelegate
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.preferences.scoringZone
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
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
    val vitalsCardConfigurations: List<CardConfiguration> = emptyList(),
    val isManagingVitalsCards: Boolean = false,
    val vitalsChartConfigurations: List<VitalsChartConfiguration> = emptyList(),
    val isManagingVitalsCharts: Boolean = false,
) {
    val isManagingVitalsLayout: Boolean
        get() = isManagingVitalsCards || isManagingVitalsCharts
}

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

private data class DatedMetrics(
    val date: LocalDate,
    val metrics: DailyMetrics?,
)

private data class DatedBodyTemperatureBaseline(
    val date: LocalDate,
    val baseline: Float?,
)

private data class VitalsChartPreferences(
    val scoringZone: ZoneId,
    val unitSystem: UnitSystem,
    val rhrBaselineOverride: Float?,
    val hrvBaselineOverride: Float?,
    val rhrOptimalThreshold: Float,
    val rhrWarningThreshold: Float,
    val hrvOptimalThreshold: Float,
    val hrvWarningThreshold: Float,
)

private data class VitalsPresentationInputs(
    val preferences: UserPreferences,
    val metrics: DatedMetrics,
    val bodyTemperatureBaseline: DatedBodyTemperatureBaseline,
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
        private val bodyTemperatureBaselineProvider: BodyTemperatureBaselineProvider,
        private val vitalsLayoutRepository: VitalsLayoutRepository,
        private val healthConnectRepository: HealthConnectRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val vitalsCardManagementDelegate =
            CardManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_VITALS_CARDS,
                persist = vitalsLayoutRepository::updateVitalsCardConfigurations,
                scope = viewModelScope,
                hasBodyTemperaturePermission = { healthConnectRepository.hasBodyTemperaturePermission() },
                hasOxygenSaturationPermission = { healthConnectRepository.hasOxygenSaturationPermission() },
            )

        private val vitalsChartManagementDelegate =
            LayoutManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_VITALS_CHARTS,
                persist = vitalsLayoutRepository::updateVitalsChartConfigurations,
                scope = viewModelScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )

        private val vitalsCardStateFlow =
            createVitalsCardStateFlow(
                cardManagementDelegate = vitalsCardManagementDelegate,
                vitalsLayoutRepository = vitalsLayoutRepository,
                healthConnectRepository = healthConnectRepository,
            ).distinctUntilChanged()

        private val vitalsChartStateFlow =
            createVitalsChartStateFlow(
                chartManagementDelegate = vitalsChartManagementDelegate,
                vitalsLayoutRepository = vitalsLayoutRepository,
            ).distinctUntilChanged()
        private val _selectedRange =
            MutableStateFlow(
                savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS,
            )
        val selectedRange: StateFlow<TimeRange> = _selectedRange.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        private val selectedMetricsFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    dailyMetricsRepository
                        .observeByDate(date)
                        .map { metrics -> DatedMetrics(date = date, metrics = metrics) }
                        .onStart { emit(DatedMetrics(date = date, metrics = null)) }
                }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val bodyTemperatureBaselineFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    bodyTemperatureBaselineProvider
                        .observeBaseline(date)
                        .map { baseline -> DatedBodyTemperatureBaseline(date = date, baseline = baseline) }
                        .onStart { emit(DatedBodyTemperatureBaseline(date = date, baseline = null)) }
                }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        private val presentationInputsFlow =
            combine(
                settingsRepo.userPreferences,
                selectedMetricsFlow,
                bodyTemperatureBaselineFlow,
            ) { prefs, metrics, bodyTemp ->
                VitalsPresentationInputs(
                    preferences = prefs,
                    metrics = metrics,
                    bodyTemperatureBaseline = bodyTemp,
                )
            }.distinctUntilChanged()
                .flowOn(ioDispatcher)

        private val chartPreferencesFlow =
            settingsRepo.userPreferences
                .map { prefs ->
                    VitalsChartPreferences(
                        scoringZone = prefs.scoringZone(),
                        unitSystem = prefs.unitSystem,
                        rhrBaselineOverride = prefs.rhrBaselineOverride,
                        hrvBaselineOverride = prefs.hrvBaselineOverride,
                        rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                        rhrWarningThreshold = prefs.rhrWarningThreshold,
                        hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                        hrvWarningThreshold = prefs.hrvWarningThreshold,
                    )
                }.distinctUntilChanged()

        private val selectionFlow =
            combine(_selectedRange, selectedDateRepository.selectedDate, ::VitalsSelection)
                .distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        private val contentFlow =
            combine(selectionFlow, chartPreferencesFlow) { selection, chartPrefs -> selection to chartPrefs }
                .flatMapLatest { selection ->
                    val (vitalsSelection, chartPrefs) = selection
                    val window =
                        resolveVitalsRangeWindow(
                            range = vitalsSelection.range,
                            selectedDate = vitalsSelection.date,
                            scoringZone = chartPrefs.scoringZone,
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
                                    vitalsSelection.range,
                                    chartPrefs.unitSystem,
                                    rhrBaselineOverride = chartPrefs.rhrBaselineOverride,
                                    hrvBaselineOverride = chartPrefs.hrvBaselineOverride,
                                    rhrOptimalThreshold = chartPrefs.rhrOptimalThreshold,
                                    rhrWarningThreshold = chartPrefs.rhrWarningThreshold,
                                    hrvOptimalThreshold = chartPrefs.hrvOptimalThreshold,
                                    hrvWarningThreshold = chartPrefs.hrvWarningThreshold,
                                    endDate = vitalsSelection.date,
                                ),
                            selection = vitalsSelection,
                            rangeStartMs = window.fromMs,
                        )
                    }.distinctUntilChanged()
                }.flowOn(ioDispatcher)
                .shareIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    replay = 1,
                )

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
                presentationInputsFlow,
                foregroundSyncController.isSyncing,
                vitalsCardStateFlow,
                vitalsChartStateFlow,
            ) { content, inputs, isSyncing, cardState, chartState ->
                val presentation =
                    buildVitalsPresentationState(
                        metrics = inputs.metrics.takeIf { it.date == content.selection.date }?.metrics,
                        summary = content.latestSummary,
                        prefs = inputs.preferences,
                        bodyTemperatureBaselineCelsius =
                            inputs.bodyTemperatureBaseline
                                .takeIf { it.date == content.selection.date }
                                ?.baseline,
                    )
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
                    vitalsCardConfigurations =
                        cardState.pendingConfiguration ?: cardState.cardConfigurations,
                    isManagingVitalsCards = cardState.isManagingCards,
                    vitalsChartConfigurations =
                        chartState.pendingConfiguration ?: chartState.chartConfigurations,
                    isManagingVitalsCharts = chartState.isManagingCharts,
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

        fun toggleVitalsCardManagement() {
            if (uiState.value.isManagingVitalsCards) {
                vitalsCardManagementDelegate.saveChanges()
            } else {
                vitalsCardManagementDelegate.enterEditMode(uiState.value.vitalsCardConfigurations)
            }
        }

        fun onCancelVitalsCardManagement() {
            vitalsCardManagementDelegate.cancelChanges()
        }

        fun onToggleVitalsCardVisibility(
            cardId: CardId,
            visible: Boolean,
        ) {
            vitalsCardManagementDelegate.onToggleCardVisibility(
                uiState.value.vitalsCardConfigurations,
                cardId,
                visible,
            )
        }

        fun onReorderVitalsCards(newOrder: List<CardConfiguration>) {
            vitalsCardManagementDelegate.onReorderCards(
                uiState.value.vitalsCardConfigurations,
                newOrder,
            )
        }

        fun onVitalsCardDisplayModeChanged(
            cardId: CardId,
            mode: DashboardCardDisplayMode,
        ) {
            vitalsCardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))
        }

        fun toggleVitalsChartManagement() {
            if (uiState.value.isManagingVitalsCharts) {
                vitalsChartManagementDelegate.saveChanges()
            } else {
                vitalsChartManagementDelegate.enterEditMode(uiState.value.vitalsChartConfigurations)
            }
        }

        fun onCancelVitalsChartManagement() {
            vitalsChartManagementDelegate.cancelChanges()
        }

        fun onToggleVitalsChartVisibility(
            chartId: VitalsChartId,
            visible: Boolean,
        ) {
            vitalsChartManagementDelegate.onToggleVisibility(
                uiState.value.vitalsChartConfigurations,
                chartId,
                visible,
            )
        }

        fun onReorderVitalsCharts(newOrder: List<VitalsChartConfiguration>) {
            vitalsChartManagementDelegate.onReorder(
                uiState.value.vitalsChartConfigurations,
                newOrder,
            )
        }

        fun onResetVitalsToDefaults() {
            vitalsCardManagementDelegate.onResetToDefaults()
            vitalsChartManagementDelegate.onResetToDefaults()
        }
    }
