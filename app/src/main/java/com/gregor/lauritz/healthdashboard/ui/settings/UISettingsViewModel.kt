package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UISettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val appConfigRepo: AppConfigRepository,
    private val healthSyncUseCase: HealthSyncUseCase,
) : ViewModel() {

    // Internal property to allow overriding in tests
    var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

    val uiState: StateFlow<UIState> by lazy {
        combine(
            prefsRepo.userPreferences,
            appConfigRepo.dynamicColorEnabled
        ) { prefs, dynamicColor ->
            UIState(
                appTheme = prefs.appTheme,
                dynamicColorEnabled = dynamicColor,
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
                viewModelScope.launch { appConfigRepo.updateAppTheme(theme = event.theme) }
            is SettingsEvent.DynamicColorEnabledChanged ->
                viewModelScope.launch { appConfigRepo.updateDynamicColorEnabled(enabled = event.enabled) }
            is SettingsEvent.PaiScalingFactorChanged ->
                viewModelScope.launch {
                    prefsRepo.updatePaiScalingFactor(value = event.value)
                    healthSyncUseCase.sync()
                }
            is SettingsEvent.StepGoalChanged ->
                viewModelScope.launch {
                    prefsRepo.updateStepGoal(steps = event.steps)
                    healthSyncUseCase.sync()
                }
            is SettingsEvent.RetentionDaysEnabledChanged ->
                viewModelScope.launch { prefsRepo.updateRetentionDaysEnabled(enabled = event.enabled) }
            is SettingsEvent.RetentionDaysChanged ->
                viewModelScope.launch { prefsRepo.updateRetentionDays(days = event.days) }
            is SettingsEvent.SectionCollapseChanged ->
                viewModelScope.launch {
                    when (event.section) {
                        SettingsSection.CLOUD_DATA -> prefsRepo.updateCollapseCloudData(collapsed = event.collapsed)
                        SettingsSection.HEALTH_CONNECT -> prefsRepo.updateCollapseHealthConnect(collapsed = event.collapsed)
                        SettingsSection.BASELINES_THRESHOLDS -> prefsRepo.updateCollapseBaselinesThresholds(collapsed = event.collapsed)
                        SettingsSection.DISPLAY -> prefsRepo.updateCollapseDisplay(collapsed = event.collapsed)
                        SettingsSection.ADVANCED -> prefsRepo.updateCollapseAdvanced(collapsed = event.collapsed)
                    }
                }
            SettingsEvent.AboutDismissed ->
                viewModelScope.launch { prefsRepo.updateAboutDismissed(dismissed = true) }
            else -> {}
        }
    }
}
