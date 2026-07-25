package app.readylytics.health.domain.model

import java.time.Instant

data class WeightRecord(
    val id: String,
    val time: Instant,
    val weightKg: Float,
    val deviceName: String? = null,
)

data class BloodPressureRecord(
    val id: String,
    val time: Instant,
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
    val deviceName: String? = null,
)

data class BodyFatRecord(
    val id: String,
    val time: Instant,
    val bodyFatPercent: Float,
    val deviceName: String? = null,
)
