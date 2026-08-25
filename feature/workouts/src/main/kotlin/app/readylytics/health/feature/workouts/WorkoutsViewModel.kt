package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.dashboard.CardManagementEvent
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.util.WeekBounds
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class WorkoutsViewModel
    @Inject
    constructor(
        private val repositories: WorkoutsRepositories,
        private val selectedDateRepository: SelectedDateStore,
        private val scoringCalculator: ScoringCalculator,
        private val settingsRepo: UserPreferencesReader,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val workoutsLayoutRepository: WorkoutsLayoutRepository,
        private val savedStateHandle: SavedStateHandle,
        private val dispatchers: WorkoutsDispatchers,
        private val useCases: WorkoutsUseCases,
    ) : ViewModel() {
        private val _selectedRange =
            MutableStateFlow(savedStateHandle.get<TimeRange>("selectedRange") ?: TimeRange.SEVEN_DAYS)
        val selectedRange = _selectedRange.asStateFlow()

        private val cardManagementDelegate =
            CardManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_CARDS,
                persist = workoutsLayoutRepository::updateWorkoutCardConfigurations,
                scope = viewModelScope,
            )

        private val chartManagementDelegate =
            LayoutManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_CHARTS,
                persist = workoutsLayoutRepository::updateWorkoutChartConfigurations,
                scope = viewModelScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )

        private val historyManagementDelegate =
            LayoutManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_HISTORY,
                persist = workoutsLayoutRepository::updateWorkoutHistoryConfigurations,
                scope = viewModelScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )

        private inner class WorkoutsLayoutManagementCoordinator {
            fun toggle() {
                val s = uiState.value
                if (s.isManagingCards) {
                    cardManagementDelegate.saveChanges()
                } else {
                    cardManagementDelegate.enterEditMode(
                        s.cardConfigurations,
                    )
                }
                if (s.isManagingCharts) {
                    chartManagementDelegate.saveChanges()
                } else {
                    chartManagementDelegate
                        .enterEditMode(
                            s.chartConfigurations,
                        )
                }
                if (s.isManagingHistory) {
                    historyManagementDelegate.saveChanges()
                } else {
                    historyManagementDelegate
                        .enterEditMode(
                            s.historyConfigurations,
                        )
                }
            }

            fun cancel() {
                cardManagementDelegate.cancelChanges()
                chartManagementDelegate.cancelChanges()
                historyManagementDelegate.cancelChanges()
            }

            fun resetToDefaults() {
                cardManagementDelegate.onResetToDefaults()
                chartManagementDelegate.onResetToDefaults()
                historyManagementDelegate.onResetToDefaults()
            }
        }

        private val layoutManagementCoordinator = WorkoutsLayoutManagementCoordinator()

        private val cardStateFlow =
            createWorkoutsCardStateFlow(cardManagementDelegate, workoutsLayoutRepository).distinctUntilChanged()
        private val chartStateFlow =
            createWorkoutsChartStateFlow(chartManagementDelegate, workoutsLayoutRepository).distinctUntilChanged()
        private val historyStateFlow =
            createWorkoutsHistoryStateFlow(historyManagementDelegate, workoutsLayoutRepository).distinctUntilChanged()

        private val isRangeChangingState = MutableStateFlow(false)

        private val _currentPage = MutableStateFlow(1)
        val currentPage = _currentPage.asStateFlow()

        private val boundaryPreferences =
            settingsRepo.userPreferences
                .map { it.scoringZone() to it.strainLoadSourceMode }
                .distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            combine(
                _selectedRange,
                selectedDateRepository.selectedDate,
                _currentPage,
            ) { range, date, page -> CombinedParams(range, date, page) }
                .scan(null as CombinedParams?) { prev, current ->
                    if (prev != null && (prev.range != current.range || prev.date != current.date)) {
                        _currentPage.value = 1
                        current.copy(page = 1)
                    } else {
                        current
                    }
                }.filterNotNull()
                .distinctUntilChanged()
                .combine(boundaryPreferences) { params, boundary -> params to boundary }
                .flatMapLatest { (params, boundary) ->
                    val zoneId = boundary.first
                    val window = resolveWorkoutsRangeWindow(params.range, params.date, zoneId)

                    val summaryFlow =
                        if (params.date == LocalDate.now(zoneId)) {
                            repositories.dailySummary.observeLatest()
                        } else {
                            flow {
                                emit(repositories.dailySummary.getByDate(window.selectedMidnightMs))
                            }.flowOn(dispatchers.io)
                        }

                    combine(
                        summaryFlow,
                        repositories.dailySummary.observeSince(window.fetchFromMs),
                        repositories.dailySummary.observeSince(window.rasFromMs),
                        settingsRepo.userPreferences,
                    ) { latest, trimpSummaries, rasSummaries, prefs ->
                        val earliestLocalDate =
                            resolveEarliestLocalDate(
                                prefs = prefs,
                                trimpSummaries = trimpSummaries,
                                zoneId = zoneId,
                                getEarliestWorkoutTimestamp = repositories.workout::getEarliestWorkoutTimestamp,
                            )

                        val pageSize = 10
                        val totalItems =
                            repositories.workout.countByTimeRange(
                                window.displayFromMs,
                                window.selectedDayEndMs,
                            )
                        val totalPages = maxOf(1, (totalItems + pageSize - 1) / pageSize)
                        val clampedPage = params.page.coerceIn(1, totalPages)
                        val pageWorkouts =
                            repositories.workout.getInRangePaged(
                                window.displayFromMs,
                                window.selectedDayEndMs,
                                pageSize,
                                (clampedPage - 1) * pageSize,
                            )

                        val recentItems = loadRecentWorkouts(pageWorkouts, prefs, trimpSummaries)
                        val workoutOnlyGains = loadWorkoutOnlyGains(window, prefs, trimpSummaries)
                        val weeklyTraining = loadWeeklyTraining(params.date, prefs, zoneId)

                        buildWorkoutsState(
                            WorkoutsStateInputs(
                                scoringCalculator = scoringCalculator,
                                latestSummary = latest,
                                trimpSummaries = trimpSummaries,
                                rasSummaries = rasSummaries,
                                prefs = prefs,
                                range = params.range,
                                selectedDate = params.date,
                                zoneId = zoneId,
                                recentWorkouts = recentItems,
                                currentPage = clampedPage,
                                totalPages = totalPages,
                                earliestLocalDate = earliestLocalDate,
                                workoutOnlyGains = workoutOnlyGains,
                                weeklyTraining = weeklyTraining,
                            ),
                        )
                    }
                }.distinctUntilChanged()
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

        private suspend fun loadRecentWorkouts(
            pageWorkouts: List<WorkoutData>,
            prefs: UserPreferences,
            trimpSummaries: List<DailySummary>,
        ): List<WorkoutDisplayItem> {
            val samplesByWorkoutId = fetchHeartRateSamplesByWorkout(pageWorkouts, repositories.heartRate)
            return pageWorkouts.map { workout ->
                val samples = samplesByWorkoutId[workout.id] ?: emptyList()
                val displayMetrics =
                    useCases.getWorkoutDisplayMetrics.execute(
                        workout = workout,
                        samples = samples,
                        preferences = prefs,
                        historicalSummaries = trimpSummaries,
                    )
                WorkoutDisplayItem(
                    workout = workout,
                    gainedStrain = displayMetrics.gainedStrain,
                    computedTrimp = displayMetrics.computedTrimp,
                    gainedStrainDisplay = displayMetrics.gainedStrainDisplay,
                    classification = displayMetrics.classification,
                )
            }
        }

        private suspend fun loadWorkoutOnlyGains(
            window: WorkoutsRangeWindow,
            prefs: UserPreferences,
            trimpSummaries: List<DailySummary>,
        ): List<Float> {
            if (prefs.strainLoadSourceMode != LoadSourceMode.WORKOUT_ONLY) return emptyList()
            val selectedDayWorkouts =
                repositories.workout.getInRange(window.selectedMidnightMs, window.selectedDayEndMs)
            val selectedDaySamples = fetchHeartRateSamplesByWorkout(selectedDayWorkouts, repositories.heartRate)
            return selectedDayWorkouts.map { workout ->
                val samples = selectedDaySamples[workout.id] ?: emptyList()
                useCases.getWorkoutDisplayMetrics
                    .execute(
                        workout = workout,
                        samples = samples,
                        preferences = prefs,
                        historicalSummaries = trimpSummaries,
                    ).gainedStrain
            }
        }

        private suspend fun loadWeeklyTraining(
            anchor: LocalDate,
            prefs: UserPreferences,
            zoneId: ZoneId,
        ): WeeklyTrainingStats {
            val fetchStart = WeekBounds.previousWeekFull(anchor, prefs.weekStartDay).start
            val fromMs = fetchStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val toMs =
                anchor
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val workouts = repositories.workout.getInRange(fromMs, toMs)
            return useCases.computeWeeklyTrainingStats.execute(workouts, anchor, prefs.weekStartDay, zoneId)
        }

        fun onRangeSelected(range: TimeRange) {
            _currentPage.value = 1
            _selectedRange.value = range
            isRangeChangingState.value = true
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
            _currentPage.value = 1
            viewModelScope.launch { selectedDateRepository.updateSelectedDate(date) }
        }

        fun onPreviousDay() {
            _currentPage.value = 1
            viewModelScope.launch { selectedDateRepository.selectPreviousDay() }
        }

        fun onNextDay() {
            _currentPage.value = 1
            viewModelScope.launch { selectedDateRepository.selectNextDay() }
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

        fun toggleWorkoutsManagement() = layoutManagementCoordinator.toggle()

        fun onCancelWorkoutsManagement() = layoutManagementCoordinator.cancel()

        fun onToggleCardVisibility(
            cardId: CardId,
            visible: Boolean,
        ) = cardManagementDelegate.onToggleCardVisibility(uiState.value.cardConfigurations, cardId, visible)

        fun onReorderCards(newOrder: List<CardConfiguration>) =
            cardManagementDelegate.onReorderCards(uiState.value.cardConfigurations, newOrder)

        fun onWorkoutsCardDisplayModeChanged(
            cardId: CardId,
            mode: DashboardCardDisplayMode,
        ) = cardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))

        fun onToggleChartVisibility(
            chartId: WorkoutChartId,
            visible: Boolean,
        ) = chartManagementDelegate.onToggleVisibility(uiState.value.chartConfigurations, chartId, visible)

        fun onReorderCharts(newOrder: List<WorkoutChartConfiguration>) =
            chartManagementDelegate.onReorder(uiState.value.chartConfigurations, newOrder)

        fun onToggleHistoryVisibility(
            historyId: WorkoutHistoryId,
            visible: Boolean,
        ) = historyManagementDelegate.onToggleVisibility(uiState.value.historyConfigurations, historyId, visible)

        fun onReorderHistory(newOrder: List<WorkoutHistoryConfiguration>) =
            historyManagementDelegate.onReorder(uiState.value.historyConfigurations, newOrder)

        fun onResetWorkoutsToDefaults() = layoutManagementCoordinator.resetToDefaults()
    }
