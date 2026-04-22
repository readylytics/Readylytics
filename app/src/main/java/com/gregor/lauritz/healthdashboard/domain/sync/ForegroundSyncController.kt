package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundSyncController
    @Inject
    constructor(
        private val prefsRepo: UserPreferencesRepository,
        private val syncUseCase: HealthSyncUseCase,
    ) {
        private val _syncCompletedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val syncCompletedEvent: SharedFlow<Unit> = _syncCompletedEvent.asSharedFlow()

        suspend fun evaluateAndSync() {
            val prefs = prefsRepo.userPreferences.first()
            when (prefs.syncPreference) {
                SyncPreference.NEVER -> return
                SyncPreference.ALWAYS -> {
                    if (prefs.lastSyncTimestamp == 0L) {
                        syncUseCase.catchUpSync()
                    } else {
                        syncUseCase.sync()
                    }
                    _syncCompletedEvent.emit(Unit)
                }
                SyncPreference.BY_TIME -> {
                    val intervalMs = prefs.syncIntervalHours * 3_600_000L
                    if (System.currentTimeMillis() - prefs.lastSyncTimestamp > intervalMs) {
                        if (prefs.lastSyncTimestamp == 0L) {
                            syncUseCase.catchUpSync()
                        } else {
                            syncUseCase.sync()
                        }
                        _syncCompletedEvent.emit(Unit)
                    }
                }
            }
        }

        suspend fun triggerImmediateSync() {
            syncUseCase.catchUpSync()
            _syncCompletedEvent.emit(Unit)
        }
    }
