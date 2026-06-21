package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import java.time.LocalDate

interface HealthChangeSynchronizer {
    suspend fun applyPendingChanges(): HealthChangeSyncOutcome

    suspend fun captureChangesTokens(): Map<HealthDataType, String>

    suspend fun commitTokens(tokens: Map<HealthDataType, String>)
}

data class HealthChangeSyncOutcome(
    val affectedDates: Set<LocalDate>,
    val requiresFullResync: Boolean,
    val nextTokens: Map<HealthDataType, String> = emptyMap(),
)
