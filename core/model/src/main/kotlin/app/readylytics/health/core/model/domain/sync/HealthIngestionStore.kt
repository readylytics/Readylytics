package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import java.time.LocalDate
import java.time.ZoneId

interface HealthIngestionStore {
    suspend fun persist(batch: HealthIngestionBatch)

    /**
     * Persists one streamed page of heart-rate samples in its own transaction (HC-001). Used by
     * [app.readylytics.health.core.healthconnect.domain.sync.HealthIngestionCoordinator]'s streamed HR ingestion so a
     * Health Connect page never waits for the rest of the window before it's written.
     */
    suspend fun persistHeartRateSamples(samples: List<HeartRateInput>)

    /** HRV equivalent of [persistHeartRateSamples]. */
    suspend fun persistHrvSamples(samples: List<HrvInput>)

    suspend fun clearFrozenBaselines(
        start: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId,
    )

    suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int
    suspend fun countHrvInRange(startMs: Long, endMs: Long): Int
    suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int
    suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int

    suspend fun persistSingleWorkoutRoute(
        workoutId: String,
        routePoints: List<WorkoutRoutePoint>,
        routeState: String,
        totalDistanceMeters: Float?,
        avgSpeedKmh: Float?,
        elevationGainMeters: Float?,
    )

    /**
     * R2-HC-001: Reconciles local database records against the set of Health Connect record IDs
     * fetched in [windowStartMs, windowEndMs] for [type]. Records in that window absent from [hcIds]
     * are deleted within a transaction, and the bounding [ScoreInvalidation.AffectedRange] of deleted
     * dates is returned (or null if no deletions occurred).
     */
    suspend fun reconcileWindow(
        type: HealthDataType,
        windowStartMs: Long,
        windowEndMs: Long,
        hcIds: Set<String>,
        zoneId: ZoneId,
    ): ScoreInvalidation.AffectedRange?
}

data class HealthIngestionBatch(
    val sleepSessions: List<SleepSessionInput>,
    val sleepStages: List<SleepStageInput>,
    val heartRateSamples: List<HeartRateInput>,
    val hrvSamples: List<HrvInput>,
    val workouts: List<WorkoutInput>,
    val weights: List<WeightInput>,
    val bodyFatSamples: List<BodyFatInput>,
    val bloodPressureSamples: List<BloodPressureInput>,
    val oxygenSaturationSamples: List<OxygenSaturationInput>,
    val bodyTemperatureSamples: List<BodyTemperatureInput>,
    val stepRecords: List<StepRecordInput>,
    val vo2MaxSamples: List<Vo2MaxInput> = emptyList(),
)

data class SleepSessionInput(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val efficiency: Float,
    val deepSleepMinutes: Int,
    val remSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val awakeMinutes: Int,
    val sleepScore: Float?,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val deviceName: String?,
)

data class SleepStageInput(
    val sessionId: String,
    val stageType: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
)

data class HeartRateInput(
    val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String?,
    val deviceName: String?,
)

data class HrvInput(
    val id: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String?,
    val deviceName: String?,
)

data class WorkoutInput(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val exerciseType: String,
    val durationMinutes: Int,
    val zone1Minutes: Float,
    val zone2Minutes: Float,
    val zone3Minutes: Float,
    val zone4Minutes: Float,
    val zone5Minutes: Float,
    val trimp: Float,
    val avgHr: Float,
    val deviceName: String?,
    val routePoints: List<WorkoutRoutePoint> = emptyList(),
    val totalDistanceMeters: Float? = null,
    val avgSpeedKmh: Float? = null,
    val elevationGainMeters: Float? = null,
    val routeState: String = RouteState.NOT_AVAILABLE,
)

data class WeightInput(
    val id: String,
    val timestampMs: Long,
    val weightKg: Float,
    val deviceName: String?,
)

data class BodyFatInput(
    val id: String,
    val timestampMs: Long,
    val bodyFatPercent: Float,
    val deviceName: String?,
)

data class BloodPressureInput(
    val id: String,
    val timestampMs: Long,
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val deviceName: String?,
)

data class OxygenSaturationInput(
    val id: String,
    val timestampMs: Long,
    val percentage: Float,
    val deviceName: String?,
)

data class BodyTemperatureInput(
    val id: String,
    val timestampMs: Long,
    val celsius: Float,
    val deviceName: String?,
)

/**
 * Raw per-record steps row, persisted purely to resolve a deleted steps record's own
 * `(startTime, endTime)` on a later `DeletionChange` (HC-005). Never read for scoring — daily step
 * totals are sourced from `StepCountFetcher`'s aggregate/device-filtered reads.
 */
data class StepRecordInput(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val count: Long,
    val deviceName: String?,
)

data class Vo2MaxInput(
    val id: String,
    val timestampMs: Long,
    val vo2Max: Float,
    val measurementMethod: Int?,
    val deviceName: String?,
)
