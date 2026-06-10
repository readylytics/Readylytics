package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.util.logE
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import com.gregor.lauritz.healthdashboard.workers.HealthResyncWorker
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val workerScheduler: WorkerScheduler,
        workManager: WorkManager,
    ) : ViewModel() {
        private val availableDevices = MutableStateFlow<List<String>>(emptyList())

        // Drives the resync button + determinate dialog directly off the worker's lifecycle/progress,
        // so the UI reflects the durable background job (it survives the screen being left and reopened).
        private val resyncWorkInfo =
            workManager.getWorkInfosForUniqueWorkFlow(WorkerScheduler.RESYNC_WORK_NAME)

        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        val uiState: StateFlow<SyncSettingsState> by lazy {
            combine(
                settingsRepo.userPreferences,
                resyncWorkInfo,
                availableDevices,
            ) { prefs, workInfos, availableDevices ->
                val info = workInfos.firstOrNull()
                val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
                val current = info?.progress?.getInt(HealthResyncWorker.KEY_CURRENT, 0) ?: 0
                val total = info?.progress?.getInt(HealthResyncWorker.KEY_TOTAL, 0) ?: 0
                SyncSettingsState(
                    syncPreference = prefs.syncPreference,
                    syncIntervalHours = prefs.syncIntervalHours,
                    isResyncing = running,
                    resyncCurrent = current,
                    resyncTotal = total,
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
                    val devices = settingsRepo.getAvailableDevices()
                    availableDevices.value = devices
                } catch (e: Exception) {
                    logE("SyncSettingsViewModel", e) { "Failed to load available devices" }
                }
            }
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.SyncPreferenceChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateSyncPreference(pref = event.pref)
                        if (event.pref == SyncPreference.ALWAYS) {
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.SyncIntervalChanged -> {
                    val validation = SettingsValidators.SYNC_INTERVAL_HOURS_RULE.validate(event.hours.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            settingsRepo.updateSyncIntervalHours(hours = event.hours)
                        }
                    }
                }
                is SettingsEvent.BackgroundSyncToggled -> {
                    viewModelScope.launch {
                        settingsRepo.updateBackgroundSyncEnabled(event.enabled)
                        if (event.enabled) {
                            val intervalMinutes = settingsRepo.backgroundSyncIntervalMinutes.first()
                            workerScheduler.schedulePeriodicSync(intervalMinutes.toLong())
                        } else {
                            workerScheduler.cancelPeriodicSync()
                        }
                    }
                }
                is SettingsEvent.BackgroundSyncIntervalChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateBackgroundSyncIntervalMinutes(event.minutes)
                        if (settingsRepo.backgroundSyncEnabled.first()) {
                            workerScheduler.schedulePeriodicSync(event.minutes.toLong())
                        }
                    }
                }
                SettingsEvent.ResyncHealthConnect -> {
                    // Enqueue the durable foreground worker; progress flows back via resyncWorkInfo.
                    viewModelScope.launch { workerScheduler.scheduleResyncWorker() }
                }
                else -> {}
            }
        }
    }
