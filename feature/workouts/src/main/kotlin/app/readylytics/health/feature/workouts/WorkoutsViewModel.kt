package app.readylytics.health.feature.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import app.readylytics.health.core.ui.common.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class WorkoutsViewModel
    @Inject
    constructor(
        private val repositories: WorkoutsRepositories,
        private val scoringCalculators: WorkoutsScoringCalculators,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val selectedRangeStore: WorkoutsSelectedRangeStore,
        private val dispatchers: WorkoutsDispatchers,
        private val useCases: WorkoutsUseCases,
        private val clock: Clock,
    ) : ViewModel(),
        WorkoutsLayoutActions {
        private val dataLoader = WorkoutsDataLoader(repositories, useCases, scoringCalculators, dispatchers.io, clock)

        private val _selectedRange =
            MutableStateFlow(selectedRangeStore.read())
        val selectedRange = _selectedRange.asStateFlow()

        private val cardManagementDelegate =
            CardManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_CARDS,
                persist = repositories.layout::updateWorkoutCardConfigurations,
                scope = viewModelScope,
            )

        private val chartManagementDelegate =
            LayoutManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_CHARTS,
                persist = repositories.layout::updateWorkoutChartConfigurations,
                scope = viewModelScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )

        private val historyManagementDelegate =
            LayoutManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_HISTORY,
                persist = repositories.layout::updateWorkoutHistoryConfigurations,
                scope = viewModelScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )

        override val layoutActionHandler: WorkoutsLayoutActionHandler =
            WorkoutsLayoutActionHandler(
                cardManagementDelegate = cardManagementDelegate,
                chartManagementDelegate = chartManagementDelegate,
                historyManagementDelegate = historyManagementDelegate,
                currentUiState = { uiState.value },
            )

        private val cardStateFlow =
            createWorkoutsCardStateFlow(cardManagementDelegate, repositories.layout).distinctUntilChanged()
        private val chartStateFlow =
            createWorkoutsChartStateFlow(chartManagementDelegate, repositories.layout).distinctUntilChanged()
        private val historyStateFlow =
            createWorkoutsHistoryStateFlow(historyManagementDelegate, repositories.layout).distinctUntilChanged()

        private val isRangeChangingState = MutableStateFlow(false)

        private val selectedFatigueRangeState = MutableStateFlow(FatigueCurveRange.ONE_DAY)

        // Ephemeral UI toggle (ACWR vs. TSB) for the training-load chart card. Not persisted and
        // deliberately combined onto uiState *after* the heavy flatMapLatest pipeline below (like
        // cardStateFlow/chartStateFlow) since dailyTsb is already computed on every pipeline run
        // regardless of which series is displayed -- flipping the toggle must not re-trigger a
        // data reload.
        private val selectedTrainingLoadMetricState = MutableStateFlow(TrainingLoadMetric.ACWR)

        private val _currentPage = MutableStateFlow(1)
        val currentPage = _currentPage.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            combine(
                _selectedRange,
                repositories.selectedDate.selectedDate,
                _currentPage,
                selectedFatigueRangeState,
            ) { range, date, page, fatigueRange -> CombinedParams(range, date, page, fatigueRange) }
                .scan(null as CombinedParams?) { prev, current ->
                    if (prev != null && (prev.range != current.range || prev.date != current.date)) {
                        _currentPage.value = 1
                        current.copy(page = 1)
                    } else {
                        current
                    }
                }.filterNotNull()
                .distinctUntilChanged()
                .combine(dataLoader.boundaryPreferences) { params, boundary -> params to boundary }
                .flatMapLatest { (params, boundary) -> dataLoader.observeState(params, zoneId = boundary.first) }
                .distinctUntilChanged()
                .map { state ->
                    isRangeChangingState.value = false
                    state
                }.combine(foregroundSyncController.isSyncing) { state, syncing ->
                    state.copy(
                        isLoading = syncing && (state.latestSummary == null && state.recentWorkouts.isEmpty()),
                        isRefreshing = syncing,
                    )
                }.combine(isRangeChangingState) { state, isChanging ->
                    state.copy(isRangeChanging = isChanging)
                }.combine(selectedTrainingLoadMetricState) { state, metric ->
                    state.copy(selectedTrainingLoadMetric = metric)
                }.combine(cardStateFlow) { state, cardState ->
                    state.copy(
                        cardConfigurations = cardState.pendingConfiguration ?: cardState.cardConfigurations,
                        isManagingCards = cardState.isManagingCards,
                    )
                }.combine(chartStateFlow) { state, chartState ->
                    state.copy(
                        chartConfigurations = chartState.pendingConfiguration ?: chartState.chartConfigurations,
                        isManagingCharts = chartState.isManagingCharts,
                    )
                }.combine(historyStateFlow) { state, historyState ->
                    state.copy(
                        historyConfigurations = historyState.pendingConfiguration ?: historyState.historyConfigurations,
                        isManagingHistory = historyState.isManagingHistory,
                    )
                }.flowOn(dispatchers.default)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = WorkoutsUiState(isLoading = true),
                )

        fun onFatigueRangeSelected(range: FatigueCurveRange) {
            selectedFatigueRangeState.value = range
        }

        fun onTrainingLoadMetricSelected(metric: TrainingLoadMetric) {
            selectedTrainingLoadMetricState.value = metric
        }

        fun onRangeSelected(range: TimeRange) {
            _currentPage.value = 1
            _selectedRange.value = range
            isRangeChangingState.value = true
            selectedRangeStore.write(range)
        }

        val earliestDate: StateFlow<LocalDate?> =
            repositories.selectedDate.earliestDate
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        fun onDateSelected(date: LocalDate) {
            _currentPage.value = 1
            viewModelScope.launch { repositories.selectedDate.updateSelectedDate(date) }
        }

        fun onPreviousDay() {
            _currentPage.value = 1
            viewModelScope.launch { repositories.selectedDate.selectPreviousDay() }
        }

        fun onNextDay() {
            _currentPage.value = 1
            viewModelScope.launch { repositories.selectedDate.selectNextDay() }
        }

        fun onNextPage() {
            val current = uiState.value.currentPage
            val totalPages = uiState.value.totalPages
            if (current < totalPages) {
                _currentPage.value = current + 1
            }
        }

        fun onPreviousPage() {
            val current = uiState.value.currentPage
            if (current > 1) {
                _currentPage.value = current - 1
            }
        }
    }
