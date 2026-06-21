package app.readylytics.health.domain.sync

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

enum class ResyncPhase {
    INGEST,
    PRUNE,
    RECONCILE,
    RECOMPUTE,
}

data class ResyncCheckpoint(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val phase: ResyncPhase,
    val nextDate: LocalDate,
    val selectionHash: String,
    val baselineChangeTokens: Map<app.readylytics.health.domain.model.HealthDataType, String> = emptyMap(),
)

interface ResyncCheckpointStore {
    val checkpoint: Flow<ResyncCheckpoint?>

    suspend fun save(checkpoint: ResyncCheckpoint)

    suspend fun clear()
}
