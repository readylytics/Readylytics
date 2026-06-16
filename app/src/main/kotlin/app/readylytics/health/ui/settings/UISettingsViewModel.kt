package app.readylytics.health.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.scoring.TrimpModel
import app.readylytics.health.domain.sync.HealthSyncUseCase
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
        private val settingsRepo: SettingsRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
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
                    viewModelScope.launch { settingsRepo.updateAppTheme(theme = event.theme) }
                is SettingsEvent.DynamicColorEnabledChanged ->
                    viewModelScope.launch { settingsRepo.updateDynamicColorEnabled(enabled = event.enabled) }
                is SettingsEvent.FallbackThemeColorChanged ->
                    viewModelScope.launch {
                        settingsRepo.updateFallbackThemeColor(color = event.color)
                        settingsRepo.updateCustomPrimaryColor(color = event.color.seedColor)
                    }
                is SettingsEvent.RasScalingFactorChanged -> {
                    val validation = SettingsValidators.RAS_SCALING_FACTOR_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            settingsRepo.updateRasScalingFactor(value = event.value)
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.StepGoalChanged -> {
                    val validation = SettingsValidators.STEP_GOAL_RULE.validate(event.steps.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            settingsRepo.updateStepGoal(steps = event.steps)
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.RetentionDaysEnabledChanged ->
                    viewModelScope.launch { settingsRepo.updateRetentionDaysEnabled(enabled = event.enabled) }
                is SettingsEvent.RetentionDaysChanged -> {
                    val validation = SettingsValidators.RETENTION_DAYS_RULE.validate(event.days.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { settingsRepo.updateRetentionDays(days = event.days) }
                    }
                }
                is SettingsEvent.TrimpModelChanged ->
                    viewModelScope.launch {
                        settingsRepo.updateTrimpModel(event.model)
                        healthSyncUseCase.sync()
                    }
                is SettingsEvent.BanisterMultiplierChanged -> {
                    val validation = SettingsValidators.TRIMP_BANISTER_MULTIPLIER_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            settingsRepo.updateBanisterMultiplier(event.value)
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.ChengBetaChanged -> {
                    val validation = SettingsValidators.TRIMP_CHENG_BETA_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            settingsRepo.updateChengBeta(event.value)
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.ItrimBChanged -> {
                    val validation = SettingsValidators.TRIMP_ITRIMP_B_FACTOR_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            settingsRepo.updateItrimB(event.value)
                            healthSyncUseCase.sync()
                        }
                    }
                }
                SettingsEvent.ResetTrimpToProfileDefaults ->
                    viewModelScope.launch {
                        val profile = settingsRepo.userPreferences.first().physiologyProfile
                        settingsRepo.updateTrimpModel(TrimpModel.BANISTER)
                        settingsRepo.updateBanisterMultiplier(profile.banisterMultiplier)
                        settingsRepo.updateChengBeta(profile.defaultChengBeta)
                        settingsRepo.updateItrimB(profile.defaultItrimB)
                        healthSyncUseCase.sync()
                    }
                is SettingsEvent.UnitSystemChanged ->
                    viewModelScope.launch { settingsRepo.updateUnitSystem(unitSystem = event.unitSystem) }
                is SettingsEvent.CustomPaletteEnabledChanged ->
                    viewModelScope.launch { settingsRepo.updateCustomPaletteEnabled(enabled = event.enabled) }
                is SettingsEvent.CustomSecondaryColorChanged ->
                    viewModelScope.launch { settingsRepo.updateCustomSecondaryColor(color = event.color) }
                is SettingsEvent.CustomTertiaryColorChanged ->
                    viewModelScope.launch { settingsRepo.updateCustomTertiaryColor(color = event.color) }
                is SettingsEvent.CustomPrimaryColorChanged ->
                    viewModelScope.launch {
                        settingsRepo.updateCustomPrimaryColor(color = event.color)
                        FallbackThemeColor.entries.find { it.seedColor == event.color }?.let {
                            settingsRepo.updateFallbackThemeColor(it)
                        }
                    }
                else -> {}
            }
        }
    }
