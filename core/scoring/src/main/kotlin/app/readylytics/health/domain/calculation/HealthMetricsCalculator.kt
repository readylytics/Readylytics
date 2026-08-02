package app.readylytics.health.domain.calculation

import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.BodyFatStatus
import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.preferences.PhysiologyProfile
import app.readylytics.health.domain.service.HealthMetricsService

object HealthMetricsCalculator {
    fun calculateBmi(
        weightKg: Float,
        heightCm: Float,
    ): Float {
        val heightM = heightCm / 100f
        return weightKg / (heightM * heightM)
    }

    /** Classify a BMI value into a [BmiStatus]. Delegates to [BodyCompositionAssessment]. */
    fun assessBmi(bmi: Float): BmiStatus = BodyCompositionAssessment.assessBmi(bmi).status

    fun assessBloodPressure(
        systolic: Int,
        diastolic: Int,
    ): BloodPressureStatus = HealthMetricsService().assessBloodPressure(systolic, diastolic)

    /**
     * Classify body-fat percentage by physiology profile and gender.
     * Delegates to [BodyCompositionAssessment]. `null` gender uses the fixed reference band.
     */
    fun assessBodyFatPercent(
        bodyFatPercent: Float,
        physiologyProfile: PhysiologyProfile,
        gender: Gender?,
    ): BodyFatStatus = BodyCompositionAssessment.assessBodyFat(bodyFatPercent, physiologyProfile, gender).status

    fun calculateDailyBpAverage(
        systolics: List<Int>,
        diastolics: List<Int>,
    ): Pair<Int, Int> {
        if (systolics.isEmpty() || diastolics.isEmpty()) return Pair(0, 0)
        return Pair(
            systolics.average().toInt(),
            diastolics.average().toInt(),
        )
    }
}
