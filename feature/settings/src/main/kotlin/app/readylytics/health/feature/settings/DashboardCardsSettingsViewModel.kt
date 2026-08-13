package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.SettingsDefaults
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
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
    ) : ViewModel() {
        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        private var applyJob: Job? = null

        private val noticeDismissed =
            settingsReader.userPreferences.map { it.bulkDisplayModeNoticeDismissed }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

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

            displaySettings.updateLastGlobalDisplayMode(null)
        }
    }
