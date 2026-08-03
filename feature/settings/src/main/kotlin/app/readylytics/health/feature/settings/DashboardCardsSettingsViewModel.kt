package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    ) : ViewModel() {
        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        private val noticeDismissed =
            settingsReader.userPreferences.map { it.bulkDisplayModeNoticeDismissed }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

        private val transientState = MutableStateFlow(DashboardCardsSettingsState())

        val uiState: StateFlow<DashboardCardsSettingsState> = transientState

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.DashboardGlobalDisplayModeApplyRequested -> {
                    if (noticeDismissed.value) {
                        viewModelScope.launch { applyGlobalMode(event.mode) }
                    } else {
                        transientState.update {
                            it.copy(showGlobalDisplayModeDialog = true, pendingGlobalDisplayMode = event.mode)
                        }
                    }
                }
                is SettingsEvent.DashboardGlobalDisplayModeConfirmed -> {
                    val mode = transientState.value.pendingGlobalDisplayMode
                    transientState.update {
                        it.copy(showGlobalDisplayModeDialog = false, pendingGlobalDisplayMode = null)
                    }
                    if (mode != null) {
                        viewModelScope.launch {
                            if (event.dontShowAgain) {
                                displaySettings.updateBulkDisplayModeNoticeDismissed(true)
                            }
                            applyGlobalMode(mode)
                        }
                    }
                }
                SettingsEvent.DashboardGlobalDisplayModeDialogDismissed -> {
                    transientState.update {
                        it.copy(showGlobalDisplayModeDialog = false, pendingGlobalDisplayMode = null)
                    }
                }
                else -> {}
            }
        }

        private suspend fun applyGlobalMode(mode: DashboardCardDisplayMode) {
            val current = cardConfigurationRepository.dashboardCardConfigurations().first()
            val updated = DashboardCardCatalog.applyGlobalDisplayMode(current, mode)
            cardConfigurationRepository.updateDashboardCardConfigurations(updated)
        }
    }
