package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.domain.preferences.DeviceSettings
import app.readylytics.health.domain.preferences.SyncSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.sync.HistoricalResyncController
import app.readylytics.health.domain.util.logE
import app.readylytics.health.domain.validation.SettingsValidators
import app.readylytics.health.domain.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSettingsViewModel
    @Inject
    constructor(
        private val settingsReader: UserPreferencesReader,
        private val syncSettings: SyncSettings,
        private val deviceSettings: DeviceSettings,
        private val healthDataRefresh: HealthDataRefresh,
        private val historicalResyncController: HistoricalResyncController,
    ) : ViewModel() {
        private val availableDevices = MutableStateFlow<List<String>>(emptyList())

        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        val uiState: StateFlow<SyncSettingsState> by lazy {
            combine(
                settingsReader.userPreferences,
                historicalResyncController.state,
                availableDevices,
            ) { prefs, resyncState, availableDevices ->
                SyncSettingsState(
                    syncPreference = prefs.syncPreference,
                    syncIntervalHours = prefs.syncIntervalHours,
                    isResyncing = resyncState.running,
                    resyncCurrent = resyncState.current,
                    resyncTotal = resyncState.total,
                    availableDevices = availableDevices,
                    primaryDeviceName = prefs.primaryDeviceName,
                    backgroundSyncEnabled = prefs.backgroundSyncEnabled,
                    backgroundSyncIntervalMinutes = prefs.backgroundSyncIntervalMinutes,
                )
            }.stateIn(
                scope = viewModelScope,
                started = sharingStarted,
                initialValue = SyncSettingsState(),
            )
        }

        init {
            loadAvailableDevices()
        }

        private fun loadAvailableDevices() {
            viewModelScope.launch {
                try {
                    val devices = deviceSettings.getAvailableDevices()
                    availableDevices.value = devices
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logE("SyncSettingsViewModel", e) { "Failed to load available devices" }
                }
            }
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.SyncPreferenceChanged -> {
                    viewModelScope.launch {
                        syncSettings.updateSyncPreference(pref = event.pref)
                        if (event.pref == SyncPreference.ALWAYS) {
                            healthDataRefresh.refreshAffectedWindow()
                        }
                    }
                }
                is SettingsEvent.SyncIntervalChanged -> {
                    val validation = SettingsValidators.SYNC_INTERVAL_HOURS_RULE.validate(event.hours.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            syncSettings.updateSyncIntervalHours(hours = event.hours)
                        }
                    }
                }
                is SettingsEvent.BackgroundSyncToggled -> {
                    viewModelScope.launch {
                        syncSettings.updateBackgroundSyncEnabled(event.enabled)
                        if (event.enabled) {
                            val intervalMinutes = uiState.value.backgroundSyncIntervalMinutes
                            historicalResyncController.schedulePeriodicSync(intervalMinutes.toLong())
                        } else {
                            historicalResyncController.cancelPeriodicSync()
                        }
                    }
                }
                is SettingsEvent.BackgroundSyncIntervalChanged -> {
                    viewModelScope.launch {
                        syncSettings.updateBackgroundSyncIntervalMinutes(event.minutes)
                        if (uiState.value.backgroundSyncEnabled) {
                            historicalResyncController.schedulePeriodicSync(event.minutes.toLong())
                        }
                    }
                }
                SettingsEvent.ResyncHealthConnect -> {
                    viewModelScope.launch { historicalResyncController.requestHistoricalResync() }
                }
                else -> {}
            }
        }
    }
