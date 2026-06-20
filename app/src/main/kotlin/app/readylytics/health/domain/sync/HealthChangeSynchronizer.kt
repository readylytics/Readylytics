package app.readylytics.health.domain.sync

import java.time.LocalDate

interface HealthChangeSynchronizer {
    suspend fun applyPendingChanges(): HealthChangeSyncOutcome

    suspend fun refreshTokensAfterFullResync()
}

data class HealthChangeSyncOutcome(
    val affectedDates: Set<LocalDate>,
    val requiresFullResync: Boolean,
)
