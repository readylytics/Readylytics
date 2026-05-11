package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectPermissionRevokedException
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
            com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "evaluateAndSync called" }
            val prefs = settingsRepo.userPreferences.first()
            when (prefs.syncPreference) {
                SyncPreference.NEVER -> {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "Sync disabled by user preference" }
                    return
                }
                SyncPreference.ALWAYS -> {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "Sync type: ALWAYS" }
                    executeSync(isFirstSync = prefs.lastSyncTimestamp == 0L)
                }
                SyncPreference.BY_TIME -> {
                    val intervalMs = prefs.syncIntervalHours * 3_600_000L
                    val timeSinceLast = System.currentTimeMillis() - prefs.lastSyncTimestamp
                    com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "Sync type: BY_TIME. Time since last: ${timeSinceLast/1000}s, Interval: ${intervalMs/1000}s" }
                    if (timeSinceLast > intervalMs) {
                        executeSync(isFirstSync = prefs.lastSyncTimestamp == 0L)
                    } else {
                        com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "Sync skipped: interval not met" }
                    }
                }
            }
        }

        suspend fun triggerImmediateSync() {
            com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "triggerImmediateSync called" }
            executeSync(isFirstSync = true)
        }

        private suspend fun executeSync(isFirstSync: Boolean) {
            _isSyncing.value = true
            try {
                if (isFirstSync) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "Running catch-up sync..." }
                    syncUseCase.catchUpSync().getOrThrow()
                } else {
                    syncUseCase.sync().getOrThrow()
                }
                com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") { "Sync success" }
                _syncCompletedEvent.emit(Unit)
            } finally {
                _isSyncing.value = false
            }
        }
    }
