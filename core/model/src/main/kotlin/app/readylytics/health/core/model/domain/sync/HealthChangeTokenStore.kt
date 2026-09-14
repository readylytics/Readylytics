package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType

interface HealthChangeTokenStore {
    suspend fun get(dataType: HealthDataType): String?

    suspend fun put(
        dataType: HealthDataType,
        token: String,
        syncedAtMs: Long,
    )

    suspend fun putAll(
        tokens: Map<HealthDataType, String>,
        syncedAtMs: Long,
    )

    suspend fun suspendType(dataType: HealthDataType)

    suspend fun isSuspended(dataType: HealthDataType): Boolean

    suspend fun clearAll()

    suspend fun getToken(tokenKey: String): String?

    suspend fun putToken(
        tokenKey: String,
        token: String,
        syncedAtMs: Long,
    )

    suspend fun suspendToken(tokenKey: String)

    suspend fun isTokenSuspended(tokenKey: String): Boolean
}
