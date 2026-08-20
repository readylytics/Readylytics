package app.readylytics.health.core.ui.common

import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiCategory
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyFatCategory
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
    // Canonical BmiCategory disambiguates Underweight vs. Overweight, both of which map to the
    // same BmiStatus.Warning — see BodyCompositionAssessment. Used only for the display label;
    // bmiStatus still drives the pill's visual (color) status.
    val bmiCategory: BmiCategory? = null,
)

data class BodyFatHistoryItem(
    val timestampMs: Long,
    val bodyFatPercent: Float,
    val leanMassDisplay: Float?,
    val unitSystem: UnitSystem,
    val status: MetricStatus,
    val category: BodyFatCategory,
)
