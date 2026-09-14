package app.readylytics.health.core.model.domain.sync

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
    val baselineChangeTokens: Map<app.readylytics.health.core.model.domain.model.HealthDataType, String> = emptyMap(),
    /**
     * HC-002: ingest chunk size (days) to resume the INGEST phase with after a Health Connect
     * window read timed out at the default chunk size and was shrunk. Null means no override --
     * use the caller-supplied `chunkDays`.
     */
    val chunkDaysOverride: Int? = null,
    /** R2-HC-002: Next page token for resuming HR stream in current chunk. Null when at start or finished. */
    val hrPageToken: String? = null,
    /** R2-HC-002: Next page token for resuming HRV stream in current chunk. Null when at start or finished. */
    val hrvPageToken: String? = null,
    /** WP-06: Health data types that have successfully completed ingestion and reconciliation. */
    val completedTypes: Set<app.readylytics.health.core.model.domain.model.HealthDataType> = emptySet(),
    /**
     * H1 review fix: whether [completedTypes] was actually populated by completedTypes-aware
     * (post-WP-06) code, as opposed to decoded from a checkpoint predating that field (or from a
     * WP-06 checkpoint that predates this flag). A freshly constructed [ResyncCheckpoint] defaults
     * to `true` because every in-code construction site in [app.readylytics.health.core.model
     * .domain.sync] callers always populates [completedTypes] accurately for the current run.
     * Only the persistence round-trip (proto decode) can observe `false`: proto3 has no scalar
     * "was this field ever set" signal, so an old serialized checkpoint that never wrote this field
     * decodes it to the proto default (false) -- exactly distinguishing "legacy/absent" (fall back
     * to the permissive baseline-tokens default) from "this run genuinely narrowed completedTypes
     * down to empty" (trust the empty set, never re-promote a type denied earlier in this same run).
     */
    val completedTypesRecorded: Boolean = true,
    /** WP-10: immutable run identity and scoring snapshot. */
    val runIdentity: HistoricalRunIdentity? = null,
)

interface ResyncCheckpointStore {
    val checkpoint: Flow<ResyncCheckpoint?>

    suspend fun save(checkpoint: ResyncCheckpoint)

    suspend fun clear()
}
