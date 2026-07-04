package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.scoring.TrimpModel
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.validation.SettingsValidators
import app.readylytics.health.domain.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UISettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: UserPreferencesReader,
        private val displaySettings: DisplaySettings,
        private val healthDataRefresh: HealthDataRefresh,
    ) : ViewModel() {
        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        val uiState: StateFlow<UIState> by lazy {
            settingsRepo.userPreferences
                .map { prefs ->
                    UIState(
                        appTheme = prefs.appTheme,
                        dynamicColorEnabled = prefs.dynamicColorEnabled,
                        fallbackThemeColor = prefs.fallbackThemeColor,
                        hrrToleranceSeconds = prefs.hrrToleranceSeconds,
                        rasScalingFactor = prefs.rasScalingFactor,
                        stepGoal = prefs.stepGoal,
                        retentionDaysEnabled = prefs.retentionDaysEnabled,
                        retentionDays = prefs.retentionDays,
                        trimpModel = prefs.trimpModel,
                        banisterMultiplier = prefs.banisterMultiplier,
                        chengBeta = prefs.chengBeta,
                        itrimB = prefs.itrimB,
                        unitSystem = prefs.unitSystem,
                        isCustomPaletteEnabled = prefs.isCustomPaletteEnabled,
                        customSecondaryColor = prefs.customSecondaryColor,
                        customTertiaryColor = prefs.customTertiaryColor,
                        customPrimaryColor = prefs.customPrimaryColor,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = sharingStarted,
                    initialValue = UIState(),
                )
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.AppThemeChanged ->
                    viewModelScope.launch { displaySettings.updateAppTheme(theme = event.theme) }
                is SettingsEvent.DynamicColorEnabledChanged ->
                    viewModelScope.launch { displaySettings.updateDynamicColorEnabled(enabled = event.enabled) }
                is SettingsEvent.FallbackThemeColorChanged ->
                    viewModelScope.launch {
                        displaySettings.updateFallbackThemeColor(color = event.color)
                        displaySettings.updateCustomPrimaryColor(color = event.color.seedColor)
                    }
                is SettingsEvent.RasScalingFactorChanged -> {
                    val validation = SettingsValidators.RAS_SCALING_FACTOR_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            displaySettings.updateRasScalingFactor(value = event.value)
                            healthDataRefresh.refreshAffectedWindow()
                        }
                    }
                }
                is SettingsEvent.StepGoalChanged -> {
                    val validation = SettingsValidators.STEP_GOAL_RULE.validate(event.steps.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            displaySettings.updateStepGoal(steps = event.steps)
                            healthDataRefresh.refreshAffectedWindow()
                        }
                    }
                }
                is SettingsEvent.HrrToleranceSecondsChanged -> {
                    val validation = SettingsValidators.HRR_TOLERANCE_RULE.validate(event.value.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            displaySettings.updateHrrToleranceSeconds(event.value)
                        }
                    }
                }
                is SettingsEvent.RetentionDaysEnabledChanged ->
                    viewModelScope.launch { displaySettings.updateRetentionDaysEnabled(enabled = event.enabled) }
                is SettingsEvent.RetentionDaysChanged -> {
                    val validation = SettingsValidators.RETENTION_DAYS_RULE.validate(event.days.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { displaySettings.updateRetentionDays(days = event.days) }
                    }
                }
                is SettingsEvent.TrimpModelChanged ->
                    viewModelScope.launch {
                        displaySettings.updateTrimpModel(event.model)
                        healthDataRefresh.refreshAffectedWindow()
                    }
                is SettingsEvent.BanisterMultiplierChanged -> {
                    val validation = SettingsValidators.TRIMP_BANISTER_MULTIPLIER_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            displaySettings.updateBanisterMultiplier(event.value)
                            healthDataRefresh.refreshAffectedWindow()
                        }
                    }
                }
                is SettingsEvent.ChengBetaChanged -> {
                    val validation = SettingsValidators.TRIMP_CHENG_BETA_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            displaySettings.updateChengBeta(event.value)
                            healthDataRefresh.refreshAffectedWindow()
                        }
                    }
                }
                is SettingsEvent.ItrimBChanged -> {
                    val validation = SettingsValidators.TRIMP_ITRIMP_B_FACTOR_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            displaySettings.updateItrimB(event.value)
                            healthDataRefresh.refreshAffectedWindow()
                        }
                    }
                }
                SettingsEvent.ResetTrimpToProfileDefaults ->
                    viewModelScope.launch {
                        val profile = settingsRepo.userPreferences.first().physiologyProfile
                        displaySettings.updateTrimpModel(TrimpModel.BANISTER)
                        displaySettings.updateBanisterMultiplier(profile.banisterMultiplier)
                        displaySettings.updateChengBeta(profile.defaultChengBeta)
                        displaySettings.updateItrimB(profile.defaultItrimB)
                        healthDataRefresh.refreshAffectedWindow()
                    }
                is SettingsEvent.UnitSystemChanged ->
                    viewModelScope.launch { displaySettings.updateUnitSystem(unitSystem = event.unitSystem) }
                is SettingsEvent.CustomPaletteEnabledChanged ->
                    viewModelScope.launch { displaySettings.updateCustomPaletteEnabled(enabled = event.enabled) }
                is SettingsEvent.CustomSecondaryColorChanged ->
                    viewModelScope.launch { displaySettings.updateCustomSecondaryColor(color = event.color) }
                is SettingsEvent.CustomTertiaryColorChanged ->
                    viewModelScope.launch { displaySettings.updateCustomTertiaryColor(color = event.color) }
                is SettingsEvent.CustomPrimaryColorChanged ->
                    viewModelScope.launch {
                        displaySettings.updateCustomPrimaryColor(color = event.color)
                        FallbackThemeColor.entries.find { it.seedColor == event.color }?.let {
                            displaySettings.updateFallbackThemeColor(it)
                        }
                    }
                else -> {}
            }
        }
    }
