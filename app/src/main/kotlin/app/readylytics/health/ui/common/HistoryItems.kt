package app.readylytics.health.ui.common

import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.MetricStatus

data class BloodPressureHistoryItem(
    val timestampMs: Long,
    val systolic: Int,
    val diastolic: Int,
    val status: BloodPressureStatus,
)

data class WeightHistoryItem(
    val timestampMs: Long,
    val weightDisplay: Float,
    val deltaDisplay: Float?,
    val unitSystem: UnitSystem,
    val bmiStatus: BmiStatus?,
)

data class BodyFatHistoryItem(
    val timestampMs: Long,
    val bodyFatPercent: Float,
    val leanMassDisplay: Float?,
    val unitSystem: UnitSystem,
    val status: MetricStatus,
)
