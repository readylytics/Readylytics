package app.readylytics.health.ui.settings.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.ui.settings.DataSourceSettingsState
import app.readylytics.health.workers.WorkerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        private val settingsRepo: SettingsRepository,
        private val workerScheduler: WorkerScheduler,
        workManager: WorkManager,
    ) : ViewModel() {
        private val _availableDevices = MutableStateFlow<List<String>>(emptyList())

        /** Per-type target value, only present when it differs from the persisted selection. */
        private val pendingOverrides = MutableStateFlow<Map<HealthDataType, String?>>(emptyMap())

        private val _showDeviceChangeNotice = MutableStateFlow(false)

        private val resyncWorkInfo =
            workManager.getWorkInfosForUniqueWorkFlow(WorkerScheduler.RESYNC_WORK_NAME)

        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        val uiState: StateFlow<DataSourceSettingsState> by lazy {
            combine(
                settingsRepo.deviceByDataType,
                _availableDevices,
                pendingOverrides,
                resyncWorkInfo,
                _showDeviceChangeNotice,
            ) { persisted, availableDevices, pending, workInfos, showNotice ->
                val effective = persisted.toMutableMap()
                pending.forEach { (type, label) ->
                    if (label == null) effective.remove(type.name) else effective[type.name] = label
                }
                val info = workInfos.firstOrNull()
                val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
                DataSourceSettingsState(
                    availableDevices = availableDevices,
                    deviceByDataType = effective,
                    hasPendingChanges = pending.isNotEmpty(),
                    isResyncing = running,
                    showDeviceChangeNotice = showNotice,
                )
            }.stateIn(
                scope = viewModelScope,
                started = sharingStarted,
                initialValue = DataSourceSettingsState(),
            )
        }

        init {
            // Make sure any legacy global device selection is reflected per data type.
            viewModelScope.launch { settingsRepo.migrateDeviceSelectionIfNeeded() }
            refreshAvailableDevices()
        }

        fun refreshAvailableDevices() {
            viewModelScope.launch {
                settingsRepo.clearDeviceCache()
                _availableDevices.value = settingsRepo.getAvailableDevices()
            }
        }

        /** Stages a device selection change; persisted only once [onApply] is called. */
        fun updateDevice(
            type: HealthDataType,
            deviceLabel: String?,
        ) {
            viewModelScope.launch {
                val persistedValue = settingsRepo.deviceByDataType.first()[type.name]
                val updated = pendingOverrides.value.toMutableMap()
                if (deviceLabel == persistedValue) {
                    updated.remove(type)
                } else {
                    updated[type] = deviceLabel
                }
                pendingOverrides.value = updated

                if (!settingsRepo.deviceChangeNoticeDismissed.first()) {
                    _showDeviceChangeNotice.value = true
                }
            }
        }

        /** Persists all staged device selections and triggers a full historical resync. */
        fun onApply() {
            viewModelScope.launch {
                val overrides = pendingOverrides.value
                if (overrides.isEmpty()) return@launch
                overrides.forEach { (type, label) ->
                    settingsRepo.updateDeviceForDataType(type.name, label)
                }
                pendingOverrides.value = emptyMap()
                workerScheduler.scheduleResyncWorker()
            }
        }

        /** Dismisses the "press Apply to resync" info dialog. */
        fun onNoticeAcknowledged(dismissPermanently: Boolean) {
            _showDeviceChangeNotice.value = false
            if (dismissPermanently) {
                viewModelScope.launch { settingsRepo.updateDeviceChangeNoticeDismissed(true) }
            }
        }
    }
