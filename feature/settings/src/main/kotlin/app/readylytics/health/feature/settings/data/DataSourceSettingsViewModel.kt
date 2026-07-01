package app.readylytics.health.feature.settings.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.DeviceSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.sync.HistoricalResyncController
import app.readylytics.health.feature.settings.DataSourceSettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the per–data-type "Data Sources" settings section. Exposes the list of
 * available source devices (phone, watches, connected apps) and the user's current
 * per-data-type selection.
 *
 * Device selection changes are staged locally in [pendingOverrides] and only
 * written through [SettingsRepository] (and trigger a full historical resync)
 * once the user presses "Apply".
 */
@HiltViewModel
class DataSourceSettingsViewModel
    @Inject
    constructor(
        private val settingsReader: UserPreferencesReader,
        private val deviceSettings: DeviceSettings,
        private val historicalResyncController: HistoricalResyncController,
    ) : ViewModel() {
        private val availableDevicesFlow = MutableStateFlow<List<String>>(emptyList())
        private val isLoadingDevicesFlow = MutableStateFlow(true)

        /** Per-type target value, only present when it differs from the persisted selection. */
        private val pendingOverrides = MutableStateFlow<Map<HealthDataType, String?>>(emptyMap())

        private val showDeviceChangeNoticeFlow = MutableStateFlow(false)

        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        private val persistedDeviceByDataType =
            settingsReader.userPreferences.map { it.deviceByDataType }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap(),
            )

        private val deviceChangeNoticeDismissed =
            settingsReader.userPreferences.map { it.deviceChangeNoticeDismissed }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

        private var applyJob: Job? = null

        val uiState: StateFlow<DataSourceSettingsState> by lazy {
            combine(
                persistedDeviceByDataType,
                availableDevicesFlow,
                pendingOverrides,
                historicalResyncController.state,
                showDeviceChangeNoticeFlow,
                isLoadingDevicesFlow,
            ) { persisted, availableDevices, pending, resyncState, showNotice, isLoadingDevices ->
                val effective = persisted.toMutableMap()
                pending.forEach { (type, label) ->
                    if (label == null) effective.remove(type.name) else effective[type.name] = label
                }
                DataSourceSettingsState(
                    availableDevices = availableDevices,
                    deviceByDataType = effective,
                    hasPendingChanges = pending.isNotEmpty(),
                    isResyncing = resyncState.running,
                    showDeviceChangeNotice = showNotice,
                    isLoadingDevices = isLoadingDevices,
                )
            }.stateIn(
                scope = viewModelScope,
                started = sharingStarted,
                initialValue = DataSourceSettingsState(),
            )
        }

        init {
            // Make sure any legacy global device selection is reflected per data type.
            viewModelScope.launch { deviceSettings.migrateDeviceSelectionIfNeeded() }
            refreshAvailableDevices()
        }

        fun refreshAvailableDevices() {
            viewModelScope.launch {
                isLoadingDevicesFlow.value = true
                try {
                    deviceSettings.clearDeviceCache()
                    availableDevicesFlow.value = deviceSettings.getAvailableDevices()
                } catch (e: Exception) {
                    availableDevicesFlow.value = emptyList()
                } finally {
                    isLoadingDevicesFlow.value = false
                }
            }
        }

        /** Stages a device selection change; persisted only once [onApply] is called. */
        fun updateDevice(
            type: HealthDataType,
            deviceLabel: String?,
        ) {
            val persistedValue = persistedDeviceByDataType.value[type.name]
            val updated = pendingOverrides.value.toMutableMap()
            if (deviceLabel == persistedValue) {
                updated.remove(type)
            } else {
                updated[type] = deviceLabel
            }
            pendingOverrides.value = updated

            if (!deviceChangeNoticeDismissed.value) {
                showDeviceChangeNoticeFlow.value = true
            }
        }

        /** Persists all staged device selections and triggers a full historical resync. */
        fun onApply() {
            if (applyJob?.isActive == true) return
            applyJob =
                viewModelScope.launch {
                    val overrides = pendingOverrides.value
                    if (overrides.isEmpty()) return@launch
                    deviceSettings.applyDeviceOverrides(overrides.mapKeys { it.key.name })
                    pendingOverrides.value = emptyMap()
                    historicalResyncController.requestHistoricalResync()
                }
        }

        /** Dismisses the "press Apply to resync" info dialog. */
        fun onNoticeAcknowledged(dismissPermanently: Boolean) {
            showDeviceChangeNoticeFlow.value = false
            if (dismissPermanently) {
                viewModelScope.launch { deviceSettings.updateDeviceChangeNoticeDismissed(true) }
            }
        }
    }
