package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.sleep.SleepCardCatalog
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardCardsSettingsViewModel
    @Inject
    constructor(
        private val settingsReader: UserPreferencesReader,
        private val displaySettings: DisplaySettings,
        private val cardConfigurationRepository: CardConfigurationRepository,
        private val vitalsLayoutRepository: VitalsLayoutRepository,
        private val sleepLayoutRepository: SleepLayoutRepository,
        private val workoutsLayoutRepository: WorkoutsLayoutRepository,
    ) : ViewModel() {
        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        private var applyJob: Job? = null

        // Eagerly is intentional, not an oversight: initialValue = false is a "not yet
        // dismissed" sentinel. Routing this through the `sharingStarted` test seam would let
        // it go cold and re-emit false on resubscribe, before the real preference reloads --
        // a dismissed notice would visibly reappear. See
        // internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md, Item 2.
        private val noticeDismissed =
            settingsReader.userPreferences.map { it.bulkDisplayModeNoticeDismissed }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

        // Eagerly is intentional, not an oversight: initialValue = null is an "unknown mode"
        // sentinel. Routing this through the `sharingStarted` test seam would let it go cold
        // and re-emit null on resubscribe, before the real preference reloads -- the global
        // display mode would briefly read as unset. See
        // internal-docs/plans/POST_REMEDIATION_FOLLOWUPS.md, Item 2.
        private val currentGlobalMode =
            settingsReader.userPreferences.map { it.lastGlobalDisplayMode }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

        private val transientState = MutableStateFlow(DashboardCardsSettingsState())

        val uiState: StateFlow<DashboardCardsSettingsState> by lazy {
            combine(transientState, currentGlobalMode) { transient, current ->
                transient.copy(currentGlobalMode = current)
            }.stateIn(
                scope = viewModelScope,
                started = sharingStarted,
                initialValue = DashboardCardsSettingsState(),
            )
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.DashboardGlobalDisplayModeApplyRequested -> {
                    if (noticeDismissed.value) {
                        if (applyJob?.isActive == true) return
                        applyJob = viewModelScope.launch { applyGlobalMode(event.mode) }
                    } else {
                        transientState.update {
                            it.copy(
                                showGlobalDisplayModeDialog = true,
                                pendingGlobalDisplayMode = event.mode,
                                pendingIsReset = false,
                            )
                        }
                    }
                }
                SettingsEvent.DashboardGlobalDisplayModeResetRequested -> {
                    if (noticeDismissed.value) {
                        if (applyJob?.isActive == true) return
                        applyJob = viewModelScope.launch { resetAllModes() }
                    } else {
                        transientState.update {
                            it.copy(
                                showGlobalDisplayModeDialog = true,
                                pendingGlobalDisplayMode = null,
                                pendingIsReset = true,
                            )
                        }
                    }
                }
                is SettingsEvent.DashboardGlobalDisplayModeConfirmed -> {
                    val pending = transientState.value
                    transientState.update {
                        it.copy(
                            showGlobalDisplayModeDialog = false,
                            pendingGlobalDisplayMode = null,
                            pendingIsReset = false,
                        )
                    }
                    if (applyJob?.isActive == true) return
                    applyJob =
                        viewModelScope.launch {
                            if (event.dontShowAgain) {
                                displaySettings.updateBulkDisplayModeNoticeDismissed(true)
                            }
                            if (pending.pendingIsReset) {
                                resetAllModes()
                            } else {
                                pending.pendingGlobalDisplayMode?.let { applyGlobalMode(it) }
                            }
                        }
                }
                SettingsEvent.DashboardGlobalDisplayModeDialogDismissed -> {
                    transientState.update {
                        it.copy(
                            showGlobalDisplayModeDialog = false,
                            pendingGlobalDisplayMode = null,
                            pendingIsReset = false,
                        )
                    }
                }
                else -> {}
            }
        }

        private suspend fun applyGlobalMode(mode: DashboardCardDisplayMode) {
            val currentDashboard = cardConfigurationRepository.dashboardCardConfigurations().first()
            val updatedDashboard = DashboardCardCatalog.applyGlobalDisplayMode(currentDashboard, mode)
            cardConfigurationRepository.updateDashboardCardConfigurations(updatedDashboard)

            val currentVitals = vitalsLayoutRepository.vitalsCardConfigurations().first()
            val updatedVitals = DashboardCardCatalog.applyGlobalDisplayMode(currentVitals, mode)
            vitalsLayoutRepository.updateVitalsCardConfigurations(updatedVitals)

            val currentSleepTopCards = sleepLayoutRepository.sleepTopCardConfigurations().first()
            sleepLayoutRepository.updateSleepTopCardConfigurations(
                SleepCardCatalog.applyGlobalTopCardMode(currentSleepTopCards, mode),
            )
            val currentSleepMetricCards = sleepLayoutRepository.sleepMetricCardConfigurations().first()
            sleepLayoutRepository.updateSleepMetricCardConfigurations(
                SleepCardCatalog.applyGlobalMetricCardMode(currentSleepMetricCards, mode),
            )

            val currentWorkoutCards = workoutsLayoutRepository.workoutCardConfigurations().first()
            val updatedWorkoutCards = DashboardCardCatalog.applyGlobalDisplayMode(currentWorkoutCards, mode)
            workoutsLayoutRepository.updateWorkoutCardConfigurations(updatedWorkoutCards)

            displaySettings.updateLastGlobalDisplayMode(mode)
        }

        private suspend fun resetAllModes() {
            val currentDashboard = cardConfigurationRepository.dashboardCardConfigurations().first()
            val updatedDashboard = DashboardCardCatalog.resetAllDisplayModes(currentDashboard)
            cardConfigurationRepository.updateDashboardCardConfigurations(updatedDashboard)

            // Dashboard cards reset to null so the catalog's VALUE legacy default applies, which is
            // their intended reset target. Vitals defaults are explicit GAUGE in
            // SettingsDefaults.DEFAULT_VITALS_CARDS, so a null-mode reset would fall through to the
            // catalog's VALUE legacy default instead of restoring GAUGE. Restore the per-card
            // default while preserving each card's current visibility and position.
            val currentVitals = vitalsLayoutRepository.vitalsCardConfigurations().first()
            val vitalsDefaultModes =
                SettingsDefaults.DEFAULT_VITALS_CARDS.associate {
                    it.cardId to
                        it.requestedDisplayMode
                }
            val updatedVitals =
                currentVitals.map { config ->
                    config.copy(requestedDisplayMode = vitalsDefaultModes[config.cardId])
                }
            vitalsLayoutRepository.updateVitalsCardConfigurations(updatedVitals)

            // Sleep defaults carry no explicit mode, so a null-mode reset falls through to the
            // per-card legacy default (gauge for the score/time top cards, value for metric cards).
            val currentSleepTopCards = sleepLayoutRepository.sleepTopCardConfigurations().first()
            sleepLayoutRepository.updateSleepTopCardConfigurations(
                SleepCardCatalog.resetTopCardModes(currentSleepTopCards),
            )
            val currentSleepMetricCards = sleepLayoutRepository.sleepMetricCardConfigurations().first()
            sleepLayoutRepository.updateSleepMetricCardConfigurations(
                SleepCardCatalog.resetMetricCardModes(currentSleepMetricCards),
            )

            // Workouts cards default to explicit modes (Strain Ratio and Readiness are gauges,
            // RAS Daily is value), so a null-mode reset would fall through to the catalog's VALUE
            // legacy default instead of restoring those. Restore the per-card default while
            // preserving each card's current visibility and position.
            val currentWorkoutCards = workoutsLayoutRepository.workoutCardConfigurations().first()
            val workoutDefaultModes =
                SettingsDefaults.DEFAULT_WORKOUT_CARDS.associate { it.cardId to it.requestedDisplayMode }
            val updatedWorkoutCards =
                currentWorkoutCards.map { config ->
                    config.copy(requestedDisplayMode = workoutDefaultModes[config.cardId])
                }
            workoutsLayoutRepository.updateWorkoutCardConfigurations(updatedWorkoutCards)

            displaySettings.updateLastGlobalDisplayMode(null)
        }
    }
