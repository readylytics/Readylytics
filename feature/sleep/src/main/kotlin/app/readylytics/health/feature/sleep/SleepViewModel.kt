package app.readylytics.health.feature.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.model.DailyMetrics
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepStageData
import app.readylytics.health.core.model.domain.sleep.SleepChartConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepChartId
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.ui.common.TimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SleepViewModel
    @Inject
    constructor(
        private val repositories: SleepRepositories,
        private val settingsRepo: UserPreferencesReader,
        private val selectedDateRepository: SelectedDateStore,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val dispatchers: SleepDispatchers,
        private val clock: Clock,
    ) : ViewModel() {
        private val layoutDelegate = SleepLayoutDelegate(repositories.sleepLayout, viewModelScope)

        private val selectedTrendRangeFlow = MutableStateFlow(TimeRange.SEVEN_DAYS)

        private val sleepScoringPrefsFlow =
            settingsRepo.userPreferences
                .map { it.toSleepScoringPrefs() }
                .distinctUntilChanged()

        @OptIn(ExperimentalCoroutinesApi::class)
        val circadianConsistencyFlow =
            selectedDateRepository.selectedDate
                .flatMapLatest { date ->
                    repositories.circadian.resultFor(date)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = CircadianConsistencyResult.Calibrating,
                )

        @OptIn(ExperimentalCoroutinesApi::class)
        private val contentStateFlow =
            combine(
                selectedDateRepository.selectedDate,
                selectedTrendRangeFlow,
                sleepScoringPrefsFlow,
            ) { date, range, prefs -> Triple(date, range, prefs) }
                .flatMapLatest { (date, range, prefs) ->
                    val scoringZoneId = prefs.scoringZoneId
                    val selectedMidnightMs =
                        date
                            .atStartOfDay(scoringZoneId)
                            .toInstant()
                            .toEpochMilli()
                    val nextDayMidnightMs =
                        date
                            .plusDays(1)
                            .atStartOfDay(scoringZoneId)
                            .toInstant()
                            .toEpochMilli()

                    val rangeStart = date.minusDays((range.days - 1).toLong())
                    val visibleRangeStartMs = rangeStart.atStartOfDay(scoringZoneId).toInstant().toEpochMilli()
                    val queryStartMs =
                        rangeStart
                            .minusDays(2)
                            .atStartOfDay(scoringZoneId)
                            .toInstant()
                            .toEpochMilli()

                    val summaryFlow =
                        if (date == LocalDate.now(clock.withZone(scoringZoneId))) {
                            val todayMs =
                                LocalDate
                                    .now(clock.withZone(scoringZoneId))
                                    .atStartOfDay(scoringZoneId)
                                    .toInstant()
                                    .toEpochMilli()
                            repositories.dailySummary
                                .observeSince(todayMs)
                                .map { it.firstOrNull() }
                        } else {
                            flow {
                                emit(
                                    repositories.dailySummary
                                        .getByDate(
                                            selectedMidnightMs,
                                        ),
                                )
                            }
                        }

                    val yesterdayMidnightMs =
                        date
                            .minusDays(1)
                            .atStartOfDay(scoringZoneId)
                            .toInstant()
                            .toEpochMilli()
                    val yesterdaySummaryFlow =
                        repositories.dailySummary.observeByDate(yesterdayMidnightMs).flowOn(dispatchers.io)

                    val sessionFlow =
                        repositories.sleepSession.observeFirstSessionEndingInRange(
                            selectedMidnightMs,
                            nextDayMidnightMs,
                        )

                    val stagesFlow =
                        sessionFlow.flatMapLatest { session ->
                            if (session == null) {
                                flowOf(emptyList())
                            } else {
                                repositories.sleepSession.observeSessionStages(session.id)
                            }
                        }

                    val hrSamplesFlow =
                        sessionFlow.flatMapLatest { session ->
                            if (session == null) {
                                flowOf(emptyList())
                            } else {
                                repositories.heartRate.observeSleepHrTimelineForSession(session.id)
                            }
                        }

                    val metricsFlow = repositories.dailyMetrics.observeByDate(date)

                    val trendSessionsFlow =
                        repositories.sleepSession.observeSince(queryStartMs).map { list ->
                            buildSleepTrendData(
                                sessions = list,
                                range = range,
                                rangeStart = rangeStart,
                                prefs = prefs,
                            )
                        }

                    combine(
                        summaryFlow,
                        sessionFlow,
                        stagesFlow,
                        metricsFlow,
                        trendSessionsFlow,
                        yesterdaySummaryFlow,
                        hrSamplesFlow,
                    ) { array ->
                        val latestSummary = array[0] as DailySummary?
                        val latestSession = array[1] as SleepSessionData?

                        @Suppress("UNCHECKED_CAST")
                        val stages = array[2] as List<SleepStageData>
                        val latestMetrics = array[3] as DailyMetrics?

                        @Suppress("UNCHECKED_CAST")
                        val trendData = array[4] as SleepTrendData
                        val yesterdaySummary = array[5] as DailySummary?

                        @Suppress("UNCHECKED_CAST")
                        val hrSamples = array[6] as List<HeartRateRecordData>

                        SleepUiState(
                            latestSummary = latestSummary,
                            latestMetrics = latestMetrics,
                            latestSession = latestSession,
                            stageTimeline = stages,
                            selectedDate = date,
                            selectedTrendRange = range,
                            trendStartOffsetPoints = trendData.startOffsetPoints,
                            trendDurationSpanPoints = trendData.durationSpanPoints,
                            trendActualDurationPoints = trendData.actualDurationPoints,
                            trendDays = trendData.trendDays,
                            trendRangeStartMs = visibleRangeStartMs,
                            trendScoringZoneId = scoringZoneId,
                            trendStartOffsetSummary = trendData.startOffsetSummary,
                            trendDurationSpanSummary = trendData.durationSpanSummary,
                            trendActualDurationSummary = trendData.actualDurationSummary,
                            goalSleepHours = prefs.goalSleepHours,
                            sleepTimeGaugeData =
                                buildSleepTimeGaugeData(
                                    session = latestSession,
                                    summary = latestSummary,
                                    goalSleepHours = prefs.goalSleepHours,
                                ),
                            yesterdaySleepScoreRounded = yesterdaySummary?.sleepScore?.roundToInt(),
                            sleepHrSamples = hrSamples,
                        )
                    }.distinctUntilChanged()
                }.flowOn(dispatchers.default)

        val uiState: StateFlow<SleepUiState> =
            combine(
                contentStateFlow,
                foregroundSyncController.isSyncing,
                layoutDelegate.layoutStateFlow,
            ) { state, syncing, layoutState ->
                val hasHistoricalData = state.trendStartOffsetPoints.any { it.value != null }
                state.copy(
                    isLoading = syncing && !hasHistoricalData,
                    isRefreshing = syncing,
                    sleepTopCardConfigurations = layoutState.sleepTopCardConfigurations,
                    isManagingSleepTopCards = layoutState.isManagingSleepTopCards,
                    sleepChartConfigurations = layoutState.sleepChartConfigurations,
                    isManagingSleepCharts = layoutState.isManagingSleepCharts,
                    sleepMetricCardConfigurations = layoutState.sleepMetricCardConfigurations,
                    isManagingSleepMetricCards = layoutState.isManagingSleepMetricCards,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue =
                    SleepUiState(
                        selectedDate = selectedDateRepository.selectedDate.value,
                        isLoading = true,
                    ),
            )

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

        fun onTrendRangeSelected(range: TimeRange) {
            selectedTrendRangeFlow.value = range
        }

        fun toggleSleepLayoutManagement() {
            val state = uiState.value
            layoutDelegate.toggleSleepLayoutManagement(
                isManaging = state.isManagingSleepLayout,
                currentTopCards = state.sleepTopCardConfigurations,
                currentCharts = state.sleepChartConfigurations,
                currentMetricCards = state.sleepMetricCardConfigurations,
            )
        }

        fun onCancelSleepLayoutManagement() {
            layoutDelegate.onCancelSleepLayoutManagement()
        }

        fun onToggleSleepTopCardVisibility(
            cardId: SleepTopCardId,
            visible: Boolean,
        ) {
            layoutDelegate.onToggleSleepTopCardVisibility(
                currentConfigs = uiState.value.sleepTopCardConfigurations,
                cardId = cardId,
                visible = visible,
            )
        }

        fun onReorderSleepTopCards(newOrder: List<SleepTopCardConfiguration>) {
            layoutDelegate.onReorderSleepTopCards(
                currentConfigs = uiState.value.sleepTopCardConfigurations,
                newOrder = newOrder,
            )
        }

        fun onSleepTopCardDisplayModeChanged(
            cardId: SleepTopCardId,
            mode: DashboardCardDisplayMode?,
        ) {
            layoutDelegate.onDisplayModeChanged(
                topCardId = cardId,
                mode = mode,
            )
        }

        fun onToggleSleepChartVisibility(
            chartId: SleepChartId,
            visible: Boolean,
        ) {
            layoutDelegate.onToggleSleepChartVisibility(
                currentConfigs = uiState.value.sleepChartConfigurations,
                chartId = chartId,
                visible = visible,
            )
        }

        fun onReorderSleepCharts(newOrder: List<SleepChartConfiguration>) {
            layoutDelegate.onReorderSleepCharts(
                currentConfigs = uiState.value.sleepChartConfigurations,
                newOrder = newOrder,
            )
        }

        fun onToggleSleepMetricCardVisibility(
            cardId: SleepMetricCardId,
            visible: Boolean,
        ) {
            layoutDelegate.onToggleSleepMetricCardVisibility(
                currentConfigs = uiState.value.sleepMetricCardConfigurations,
                cardId = cardId,
                visible = visible,
            )
        }

        fun onReorderSleepMetricCards(newOrder: List<SleepMetricCardConfiguration>) {
            layoutDelegate.onReorderSleepMetricCards(
                currentConfigs = uiState.value.sleepMetricCardConfigurations,
                newOrder = newOrder,
            )
        }

        fun onSleepMetricCardDisplayModeChanged(
            cardId: SleepMetricCardId,
            mode: DashboardCardDisplayMode?,
        ) {
            layoutDelegate.onDisplayModeChanged(
                metricCardId = cardId,
                mode = mode,
            )
        }

        fun onResetSleepLayoutToDefaults() {
            layoutDelegate.onResetSleepLayoutToDefaults()
        }
    }
