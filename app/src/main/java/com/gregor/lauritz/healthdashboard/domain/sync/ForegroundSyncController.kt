package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundSyncController
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val syncUseCase: HealthSyncUseCase,
    ) {
        private val _syncCompletedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val syncCompletedEvent: SharedFlow<Unit> = _syncCompletedEvent.asSharedFlow()

        private val _isSyncing = MutableStateFlow(false)
        val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

        suspend fun evaluateAndSync() {
            val prefs = settingsRepo.userPreferences.first()
            when (prefs.syncPreference) {
                SyncPreference.NEVER -> return
                SyncPreference.ALWAYS -> {
                    _isSyncing.value = true
                    try {
                        if (prefs.lastSyncTimestamp == 0L) {
                            syncUseCase.catchUpSync()
                        } else {
                            syncUseCase.sync()
                        }
                        _syncCompletedEvent.emit(Unit)
                    } finally {
                        _isSyncing.value = false
                    }
                }
                SyncPreference.BY_TIME -> {
                    val intervalMs = prefs.syncIntervalHours * 3_600_000L
                    if (System.currentTimeMillis() - prefs.lastSyncTimestamp > intervalMs) {
                        _isSyncing.value = true
                        try {
                            if (prefs.lastSyncTimestamp == 0L) {
                                syncUseCase.catchUpSync()
                            } else {
                                syncUseCase.sync()
                            }
                            _syncCompletedEvent.emit(Unit)
                        } finally {
                            _isSyncing.value = false
                        }
                    }
                }
            }
        }

        suspend fun triggerImmediateSync() {
            _isSyncing.value = true
            try {
                syncUseCase.catchUpSync()
                _syncCompletedEvent.emit(Unit)
            } finally {
                _isSyncing.value = false
            }
        }
    }
