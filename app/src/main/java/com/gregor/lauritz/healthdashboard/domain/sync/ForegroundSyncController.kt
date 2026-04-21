package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
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
        suspend fun evaluateAndSync() {
            val prefs = prefsRepo.userPreferences.first()
            when (prefs.syncPreference) {
                SyncPreference.NEVER -> return
                SyncPreference.ALWAYS -> syncUseCase.sync()
                SyncPreference.BY_TIME -> {
                    val intervalMs = prefs.syncIntervalHours * 3_600_000L
                    if (System.currentTimeMillis() - prefs.lastSyncTimestamp > intervalMs) {
                        syncUseCase.sync()
                    }
                }
            }
        }

        suspend fun triggerImmediateSync() {
            syncUseCase.catchUpSync()
        }
    }
