package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UISettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val healthSyncUseCase: HealthSyncUseCase,
) : ViewModel() {

    // Internal property to allow overriding in tests
    var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

    val uiState: StateFlow<UIState> by lazy {
        settingsRepo.userPreferences.map { prefs ->
            UIState(
                appTheme = prefs.appTheme,
                dynamicColorEnabled = prefs.dynamicColorEnabled,
                paiScalingFactor = prefs.paiScalingFactor,
                stepGoal = prefs.stepGoal,
                retentionDaysEnabled = prefs.retentionDaysEnabled,
                retentionDays = prefs.retentionDays,
                collapseCloudData = prefs.collapseCloudData,
                collapseHealthConnect = prefs.collapseHealthConnect,
                collapseBaselinesThresholds = prefs.collapseBaselinesThresholds,
                collapseDisplay = prefs.collapseDisplay,
                collapseAdvanced = prefs.collapseAdvanced,
                aboutDismissed = prefs.aboutDismissed,
            )
        }.stateIn(
            scope = viewModelScope,
            started = sharingStarted,
            initialValue = UIState()
        )
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.AppThemeChanged ->
                viewModelScope.launch { settingsRepo.updateAppTheme(theme = event.theme) }
            is SettingsEvent.DynamicColorEnabledChanged ->
                viewModelScope.launch { settingsRepo.updateDynamicColorEnabled(enabled = event.enabled) }
            is SettingsEvent.PaiScalingFactorChanged ->
                viewModelScope.launch {
                    settingsRepo.updatePaiScalingFactor(value = event.value)
                    healthSyncUseCase.sync()
                }
            is SettingsEvent.StepGoalChanged ->
                viewModelScope.launch {
                    settingsRepo.updateStepGoal(steps = event.steps)
                    healthSyncUseCase.sync()
                }
            is SettingsEvent.RetentionDaysEnabledChanged ->
                viewModelScope.launch { settingsRepo.updateRetentionDaysEnabled(enabled = event.enabled) }
            is SettingsEvent.RetentionDaysChanged ->
                viewModelScope.launch { settingsRepo.updateRetentionDays(days = event.days) }
            is SettingsEvent.SectionCollapseChanged ->
                viewModelScope.launch {
                    when (event.section) {
                        SettingsSection.CLOUD_DATA -> settingsRepo.updateCollapseCloudData(collapsed = event.collapsed)
                        SettingsSection.HEALTH_CONNECT -> settingsRepo.updateCollapseHealthConnect(collapsed = event.collapsed)
                        SettingsSection.BASELINES_THRESHOLDS -> settingsRepo.updateCollapseBaselinesThresholds(collapsed = event.collapsed)
                        SettingsSection.DISPLAY -> settingsRepo.updateCollapseDisplay(collapsed = event.collapsed)
                        SettingsSection.ADVANCED -> settingsRepo.updateCollapseAdvanced(collapsed = event.collapsed)
                    }
                }
            SettingsEvent.AboutDismissed ->
                viewModelScope.launch { settingsRepo.updateAboutDismissed(dismissed = true) }
            else -> {}
        }
    }
}
