package com.gregor.lauritz.healthdashboard.data.healthconnect

import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import com.gregor.lauritz.healthdashboard.domain.util.logD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that tracks Health Connect permission grant state across the entire
 * process. Acts as the single source of truth so the sync use-case, the UI
 * banner, and WorkManager all agree on whether reads should be attempted.
 *
 * Responsibilities:
 * - Snapshot per-record-type revocation timestamps (when did the user revoke X?).
 * - Aggregate them into a [HealthConnectPermissionState] for UI consumption.
 * - Provide an idempotent `onPermissionRevoked` hook for repository code so
 *   a SecurityException at read-time can flip the global state without
 *   waiting for the next explicit `checkPermissions()` call.
 *
 * Note: the in-memory map is the authoritative session state. Long-term
 * persistence of "user explicitly revoked this on date X" lives in
 * [com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository] (see
 * `hcPermissionsRevoked` / `hcSyncDisabledReason`). This class does not write to
 * DataStore directly so it stays unit-testable.
 */
@Singleton
class HealthConnectPermissionManager
    @Inject
    constructor(
        private val healthConnectRepository: HealthConnectRepository,
    ) {
        private val mutex = Mutex()

        // recordType (HealthPermission string) -> timestamp of most-recent revocation
        private val revokedAt: MutableMap<String, Long> = mutableMapOf()

        private val _state = MutableStateFlow<HealthConnectPermissionState>(
            HealthConnectPermissionState.Unknown,
        )

        /** Observable aggregate state for ViewModels and banners. */
        val state: StateFlow<HealthConnectPermissionState> = _state.asStateFlow()

        @Volatile
        var lastCheckTimestamp: Long = 0L
            private set

        /** True iff the supplied record-type / permission string is currently granted. */
        fun hasPermission(recordType: String): Boolean =
            !revokedAt.containsKey(recordType)

        /**
         * Refresh aggregate state by asking Health Connect for the granted-permission set.
         * Updates [state] in place and returns the new aggregate.
         */
        suspend fun checkPermissions(): HealthConnectPermissionState =
            mutex.withLock {
                val status = healthConnectRepository.checkPermissions()
                lastCheckTimestamp = System.currentTimeMillis()
                val required = healthConnectRepository.criticalPermissions
                val newState =
                    when (status) {
                        is PermissionStatus.Granted -> {
                            revokedAt.clear()
                            HealthConnectPermissionState.Granted
                        }
                        is PermissionStatus.Unavailable -> {
                            // SDK absent — treat as Unknown rather than Revoked since the user
                            // hasn't actively withdrawn consent.
                            HealthConnectPermissionState.Unknown
                        }
                        is PermissionStatus.Missing -> {
                            val criticalMissing = (required intersect status.missing).toList()
                            criticalMissing.forEach { rt ->
                                revokedAt.putIfAbsent(rt, lastCheckTimestamp)
                            }
                            // Drop entries that became granted again.
                            revokedAt.keys.retainAll { it in status.missing }
                            when {
                                criticalMissing.isEmpty() -> HealthConnectPermissionState.Granted
                                criticalMissing.size == required.size -> HealthConnectPermissionState.Revoked
                                else -> HealthConnectPermissionState.PartiallyRevoked(criticalMissing.sorted())
                            }
                        }
                    }
                _state.value = newState
                logD("HCPermissionMgr") { "checkPermissions: $newState (revokedAt=$revokedAt)" }
                newState
            }

        /**
         * Mark a single record type as revoked. Idempotent: callable from any
         * thread / coroutine after a SecurityException is observed during a read.
         *
         * Updates [state] best-effort: if the record type is part of the critical
         * set the aggregate becomes [HealthConnectPermissionState.PartiallyRevoked]
         * (or `Revoked` if every critical type is now missing).
         */
        fun onPermissionRevoked(recordType: String) {
            val now = System.currentTimeMillis()
            revokedAt[recordType] = now
            val required = healthConnectRepository.criticalPermissions
            val missing = revokedAt.keys.intersect(required).sorted()
            val newState =
                when {
                    missing.isEmpty() -> _state.value
                    missing.size == required.size -> HealthConnectPermissionState.Revoked
                    else -> HealthConnectPermissionState.PartiallyRevoked(missing)
                }
            _state.value = newState
            logD("HCPermissionMgr") { "Permission revoked for $recordType; sync disabled. state=$newState" }
        }

        /** Permissions known to be revoked right now. Snapshot. */
        fun revokedPermissions(): List<String> = revokedAt.keys.sorted()

        /**
         * Whether sync is currently disabled because of revoked permissions.
         * Sync should be paused for any state other than Granted/Unknown.
         */
        fun isSyncDisabled(): Boolean =
            when (_state.value) {
                is HealthConnectPermissionState.Revoked,
                is HealthConnectPermissionState.PartiallyRevoked,
                -> true
                else -> false
            }
    }
