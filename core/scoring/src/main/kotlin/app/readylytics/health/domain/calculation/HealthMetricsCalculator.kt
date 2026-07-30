package app.readylytics.health.domain.calculation

import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.BodyFatStatus
import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.preferences.PhysiologyProfile

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
    ): BloodPressureStatus =
        when {
            systolic <= 120 && diastolic < 80 -> BloodPressureStatus.Optimal
            systolic <= 129 && diastolic < 80 -> BloodPressureStatus.Neutral
            systolic in 130..139 || diastolic in 80..89 -> BloodPressureStatus.HypertensionStage1
            else -> BloodPressureStatus.HypertensionStage2
        }

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
