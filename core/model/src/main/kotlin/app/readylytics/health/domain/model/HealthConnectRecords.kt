package app.readylytics.health.domain.model

import java.time.Instant

data class DomainSleepSessionRecord(
    val id: String,
    val startTime: Instant,
    val endTime: Instant,
    val startZoneOffsetSeconds: Int?,
    val endZoneOffsetSeconds: Int?,
    val deviceName: String,
    val stages: List<DomainSleepStage>,
)

data class DomainSleepStage(
    val startTime: Instant,
    val endTime: Instant,
    val stageType: DomainSleepStageType,
)

enum class DomainSleepStageType {
    DEEP,
    REM,
    LIGHT,
    AWAKE,
    UNKNOWN,
}

data class DomainHeartRateRecord(
    val id: String,
    val deviceName: String,
    val samples: List<DomainHeartRateSample>,
)

data class DomainHeartRateSample(
    val time: Instant,
    val beatsPerMinute: Int,
)

data class DomainHrvRecord(
    val id: String,
    val time: Instant,
    val rmssdMs: Float,
    val deviceName: String,
)

data class DomainExerciseSessionRecord(
    val id: String,
    val startTime: Instant,
    val endTime: Instant,
    val exerciseType: String,
    val deviceName: String,
)

data class DomainStepsRecord(
    val startTime: Instant,
    val count: Long,
    val deviceName: String,
)

data class DomainWeightRecord(
    val id: String,
    val time: Instant,
    val weightKg: Float,
    val deviceName: String,
)

data class DomainBodyFatRecord(
    val id: String,
    val time: Instant,
    val percentage: Float,
    val deviceName: String,
)

data class DomainBloodPressureRecord(
    val id: String,
    val time: Instant,
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val deviceName: String,
)

data class DomainOxygenSaturationRecord(
    val id: String,
    val time: Instant,
    val percentage: Float,
    val deviceName: String,
)

data class DomainRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestampMs: Long,
    val horizontalAccuracy: Float?,
    val verticalAccuracy: Float?
)

data class DomainExerciseRoute(
    val workoutId: String,
    val points: List<DomainRoutePoint>
)

