package app.readylytics.health.domain.sync

import java.time.LocalDate
import java.time.ZoneId

interface HealthIngestionStore {
    suspend fun persist(batch: HealthIngestionBatch)

    suspend fun clearFrozenBaselines(
        start: LocalDate,
        endExclusive: LocalDate,
        zoneId: ZoneId,
    )

    suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int
    suspend fun countHrvInRange(startMs: Long, endMs: Long): Int
    suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int
    suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int
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
    val stepRecords: List<StepRecordInput>,
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
