package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
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
    /** Why [requiresFullResync] was set (diagnostics only; empty when it is false). */
    val fullResyncReason: String = "",
) {
    companion object {
        fun fullResync(reason: String): HealthChangeSyncOutcome =
            HealthChangeSyncOutcome(affectedDates = emptySet(), requiresFullResync = true, fullResyncReason = reason)
    }
}
