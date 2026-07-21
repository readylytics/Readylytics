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
    /** Mandatory baseline tokens for ingesting full resyncs; deliberately empty for local recomputes. */
    val baselineChangeTokens: Map<app.readylytics.health.domain.model.HealthDataType, String> = emptyMap(),
    /**
     * HC-002: ingest chunk size (days) to resume the INGEST phase with after a Health Connect
     * window read timed out at the default chunk size and was shrunk. Null means no override --
     * use the caller-supplied `chunkDays`.
     */
    val chunkDaysOverride: Int? = null,
)

interface ResyncCheckpointStore {
    val checkpoint: Flow<ResyncCheckpoint?>

    suspend fun save(checkpoint: ResyncCheckpoint)

    suspend fun clear()
}
