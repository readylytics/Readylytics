package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import java.time.LocalDate

interface HealthChangeSynchronizer {
    suspend fun applyPendingChanges(): HealthChangeSyncOutcome

    suspend fun captureChangesTokens(): CapturedChangeTokens

    suspend fun commitTokens(typed: Map<HealthDataType, String>, intervals: Map<String, String>)
}

data class CapturedChangeTokens(
    val typed: Map<HealthDataType, String> = emptyMap(),
    val intervals: Map<String, String> = emptyMap(),
)

data class IntervalTokenProgress(
    val baseline: Map<String, String> = emptyMap(),
    val completed: Set<String> = emptySet(),
)

data class HealthChangeSyncOutcome(
    val affectedDates: Set<LocalDate>,
    val requiresFullResync: Boolean,
    val continuationRequired: Boolean = false,
    val nextTokens: Map<HealthDataType, String> = emptyMap(),
    val nextIntervalTokens: Map<String, String> = emptyMap(),
    /** Why [requiresFullResync] was set (diagnostics only; empty when it is false). */
    val fullResyncReason: String = "",
) {
    companion object {
        fun fullResync(reason: String): HealthChangeSyncOutcome =
            HealthChangeSyncOutcome(affectedDates = emptySet(), requiresFullResync = true, fullResyncReason = reason)
    }
}
