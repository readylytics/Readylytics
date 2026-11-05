package app.readylytics.health.feature.dashboard

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.model.DomainError
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionChecker
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.scoring.domain.airecommendation.DailyPromptFormatter
import app.readylytics.health.core.ui.common.BaseViewModel
import app.readylytics.health.core.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import app.readylytics.health.core.ui.R as CoreUiR

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val repositories: DashboardRepositories,
        private val useCases: DashboardUseCases,
        private val foregroundSyncController: ForegroundSyncGateway,
        private val fatigueTicker: DashboardFatigueTicker,
        private val permissionChecker: HealthConnectPermissionChecker,
        private val clock: Clock,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
        private val stateTransformer: DashboardUiStateTransformer = DashboardUiStateTransformer(useCases, clock),
    ) : BaseViewModel(),
        DashboardCardActions {
        fun validateSelectedDate(date: LocalDate): Result<LocalDate> =
            if (date <= LocalDate.now(clock)) Result.success(date) else Result.failure(DomainError.InvalidDate)

        private val cardManagementDelegate =
            CardManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                persist = repositories.cardConfig::updateDashboardCardConfigurations,
                scope = viewModelScope,
                hasBodyTemperaturePermission = permissionChecker::hasBodyTemperaturePermission,
                hasStepsPermission = permissionChecker::hasStepsPermission,
                hasWeightPermission = permissionChecker::hasWeightPermission,
                hasBodyFatPermission = permissionChecker::hasBodyFatPermission,
                hasBloodPressurePermission = permissionChecker::hasBloodPressurePermission,
                hasOxygenSaturationPermission = permissionChecker::hasOxygenSaturationPermission,
            )

        override val cardActionHandler: DashboardCardActionHandler =
            DashboardCardActionHandler(cardManagementDelegate) { uiState.value.cardConfigurations }

        val isManagingCards: StateFlow<Boolean> = cardManagementDelegate.isManagingCards

        val uiState: StateFlow<DashboardUiState> =
            // The expensive transform (InsightEngine + GetDashboardDataUseCase) is driven only
            // by the data flows (basic/card/hr). Realtime sync state is merged in afterwards via
            // a cheap copy, so recalcProgress/isSyncing ticks during a resync no longer re-run
            // insight evaluation and card building. distinctUntilChanged guards the derived core
            // flow (a cold combine of multiple sources) against equal re-emissions.
            combine(
                createDashboardBasicInputsFlow(
                    repositories.selectedDate.selectedDate,
                    repositories.dailySummary,
                    repositories.settings,
                    repositories.circadian,
                    repositories.insightDismissal,
                    repositories.bodyTemperatureBaselineProvider,
                    clock,
                ),
                createDashboardCardStateFlow(
                    repositories.selectedDate.selectedDate,
                    cardManagementDelegate,
                    repositories.cardConfig,
                    repositories.dailySummary,
                    permissionChecker,
                    repositories.settings,
                ),
                createDashboardHrFlow(
                    repositories.selectedDate.selectedDate,
                    repositories.heartRate,
                    repositories.settings,
                ),
                useCases.observeDashboardStrainIncrease(
                    repositories.selectedDate.selectedDate,
                    repositories.settings.userPreferences,
                ),
                // Paired rather than passed as a 6th source: the typed `combine` overloads stop at
                // five. The ticker re-runs this transform once a minute so live residual fatigue
                // keeps decaying on a dashboard nothing else is emitting into.
                combine(
                    useCases.observeDashboardRasIncrease(
                        repositories.selectedDate.selectedDate,
                        repositories.settings.userPreferences,
                    ),
                    fatigueTicker.minuteBuckets(),
                ) { rasIncrease, minuteBucket -> rasIncrease to minuteBucket },
            ) { basicInputs, cardState, hrSummary, todayStrainIncrease, (todayRasIncrease, minuteBucket) ->
                stateTransformer.transformToUiState(
                    basicInputs,
                    cardState,
                    minuteBucket,
                    hrSummary,
                    todayStrainIncrease,
                    todayRasIncrease,
                )
            }.distinctUntilChanged()
                .combine(createDashboardRealtimeStateFlow(foregroundSyncController)) { coreState, realtimeState ->
                    coreState.copy(
                        isRefreshing = realtimeState.isSyncing,
                        recalcProgress = realtimeState.recalcProgress,
                        isComputingMetrics = realtimeState.isSyncing && coreState.summary == null,
                    )
                }.flowOn(defaultDispatcher)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DashboardUiState(selectedDate = LocalDate.now(clock), today = LocalDate.now(clock)),
                )

        val earliestDate: StateFlow<LocalDate?> =
            repositories.selectedDate.earliestDate.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null,
            )

        fun onPreviousDay() {
            viewModelScope.launch { repositories.selectedDate.selectPreviousDay() }
        }

        fun onNextDay() {
            viewModelScope.launch { repositories.selectedDate.selectNextDay() }
        }

        fun onEvent(event: DashboardEvent) {
            when (event) {
                is DashboardEvent.DateSelected ->
                    viewModelScope.launch {
                        if (validateSelectedDate(event.date).isSuccess) {
                            repositories.selectedDate.updateSelectedDate(event.date)
                        } else {
                            _errorMessage.value = UiText.StringRes(R.string.error_cannot_select_future_dates)
                        }
                    }
                DashboardEvent.PreviousDay -> onPreviousDay()
                DashboardEvent.NextDay -> onNextDay()
                DashboardEvent.Refresh -> onRefresh()
                DashboardEvent.ToggleCardManagement -> toggleCardManagement()
                is DashboardEvent.DismissInsight ->
                    viewModelScope.launch {
                        repositories.insightDismissal.dismiss(selectedDateMs(), event.type)
                    }
                DashboardEvent.RestoreInsights ->
                    viewModelScope.launch {
                        repositories.insightDismissal.restoreAllForDate(selectedDateMs())
                    }
                DashboardEvent.RequestDailyPromptCopy ->
                    viewModelScope.launch {
                        try {
                            val zoneId =
                                repositories.settings.userPreferences
                                    .first()
                                    .scoringZone()
                            val text = generateDailyPrompt(LocalDate.now(clock.withZone(zoneId)))
                            _dailyPromptText.value = PromptRequest(text, promptRequestSeq++)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            app.readylytics.health.core.model.domain.util
                                .logE(TAG, e) { "Failed to generate daily prompt" }
                            _errorMessage.value = UiText.StringRes(R.string.ai_recommendation_copy_failed)
                        }
                    }
            }
        }

        private suspend fun selectedDateMs(): Long {
            val zoneId =
                repositories.settings.userPreferences
                    .first()
                    .scoringZone()
            return repositories.selectedDate.selectedDate.value
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        }

        internal suspend fun generateDailyPrompt(today: LocalDate): String =
            withContext(defaultDispatcher) {
                DailyPromptFormatter.format(useCases.getDailyPromptData.execute(today))
            }

        fun onRefresh() {
            viewModelScope.launch {
                try {
                    // Pull-to-refresh recalculates the current day only; the Settings
                    // "Resync Health Connect data" button drives the full historical resync.
                    foregroundSyncController.triggerDailySync()
                } catch (e: Exception) {
                    app.readylytics.health.core.model.domain.util
                        .logE(TAG, e) { "Refresh failed" }
                    _errorMessage.value = UiText.StringRes(CoreUiR.string.error_sync_failed)
                } finally {
                    // Always clear cached derived metrics, even if the sync failed partway, so the
                    // dashboard never serves stale sleep/load scores from a previous recalculation.
                    repositories.dailyMetricCache.invalidate()
                }
            }
        }

        private val _errorMessage = MutableStateFlow<UiText?>(null)
        val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

        private var promptRequestSeq = 0
        private val _dailyPromptText = MutableStateFlow<PromptRequest?>(null)
        val dailyPromptText: StateFlow<PromptRequest?> = _dailyPromptText.asStateFlow()

        fun clearDailyPromptText() {
            _dailyPromptText.value = null
        }

        companion object {
            internal const val TAG = "DashboardViewModel"
        }
    }
