package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType

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

    suspend fun clear(dataType: HealthDataType)

    suspend fun clearAll()
}
