package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.HealthDataType
import java.time.LocalDate
import java.time.ZoneId

/**
 * R2-ARCH-002/WP-16: per-record operations the Health Connect Changes API path
 * (`HealthChangeSynchronizerImpl`) needs, which the bulk/resync path's [HealthIngestionStore] has
 * no use for (that path always deletes/reconciles a whole window, never one record at a time).
 * Kept as its own interface rather than folded into [HealthIngestionStore] so neither interface
 * crosses detekt's `TooManyFunctions` threshold.
 */
interface HealthChangeIngestionStore {
    /**
     * Resolves the calendar dates one already-persisted HC record with [hcRecordId] currently
     * occupies, before it is deleted and replaced by an upsert (or removed outright by a
     * deletion). Mirrors the deleted-record date lookup the Changes API path needs per record,
     * since it processes one change at a time rather than a whole window.
     */
    suspend fun affectedDatesForRecord(
        type: HealthDataType,
        hcRecordId: String,
        zoneId: ZoneId,
    ): Set<LocalDate>

    /** Deletes the local row(s) owned by one HC record, by type. */
    suspend fun deleteRecord(type: HealthDataType, hcRecordId: String)

    /**
     * Sleep and workout session spans whose [SleepSessionInput]/[WorkoutInput] time range
     * overlaps `[startMs, endMs]`, for `SessionLinkSweep`-based session tagging of a changes-path
     * record (or page of records) at write time (HC-004).
     */
    suspend fun sessionSpansOverlapping(startMs: Long, endMs: Long): SessionSpans

    /**
     * Already-persisted heart-rate samples of [recordType] in `[startMs, endMs]`, mapped to the
     * domain sample shape. Used by the Changes API path to compute provisional workout
     * TRIMP/zones/avgHr from already-stored HR at upsert time (HC-004) without needing direct
     * `HeartRateDao` access.
     */
    suspend fun heartRateSamplesForMetrics(
        recordType: String,
        startMs: Long,
        endMs: Long,
    ): List<DomainHeartRateSample>
}

data class SessionSpans(
    val sleepSessions: List<SleepSessionInput>,
    val workouts: List<WorkoutInput>,
)
