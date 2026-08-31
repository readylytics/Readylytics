package app.readylytics.health.core.model.domain.model

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
    val routePoints: List<DomainRouteLocation> = emptyList(),
    val totalDistanceMeters: Double? = null,
    val avgSpeedMps: Double? = null,
    val elevationGainMeters: Double? = null,
    val routeState: String = RouteState.NOT_AVAILABLE,
)

data class DomainRouteLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val time: Instant,
    val horizontalAccuracyMeters: Float?,
    val verticalAccuracyMeters: Float?,
)

object RouteState {
    const val IMPORTED = "IMPORTED"
    const val PERMISSION_REQUIRED = "PERMISSION_REQUIRED"
    const val NOT_AVAILABLE = "NOT_AVAILABLE"
}

/**
 * A Health Connect interval record carrying a single cumulative quantity (metres of distance,
 * metres of elevation gained) over its own time span. Kept provider-agnostic so the attribution
 * rule in `SessionTotalsResolver` stays pure Kotlin.
 */
data class DomainIntervalTotal(
    val startTime: Instant,
    val endTime: Instant,
    val value: Double,
    val originPackage: String,
)

data class DomainStepsRecord(
    val id: String,
    val startTime: Instant,
    val endTime: Instant,
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

data class DomainBodyTemperatureRecord(
    val id: String,
    val time: Instant,
    val celsius: Float,
    val deviceName: String,
)
