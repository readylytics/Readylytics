package app.readylytics.health.core.model.domain.service

import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyCompositionAssessment
import app.readylytics.health.domain.model.BodyFatStatus
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.HealthZone
import app.readylytics.health.domain.model.ZoneBand
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile

/**
 * Pure-Kotlin facade for cross-metric health calculations.
 *
 * Returns [Result]s with stable failure codes ([Codes]) so callers can pattern-match on
 * predictable outcomes instead of dealing with `null` or exceptions.
 */
class HealthMetricsService {
    /**
     * BMI from weight (kg) and height (cm). Returns [Result.Failure] for invalid input.
     */
    fun calculateBmi(
        weightKg: Float,
        heightCm: Float,
    ): Result<Float> {
        if (weightKg <= 0f) return Result.Failure("Weight must be positive", Codes.INVALID_WEIGHT)
        if (heightCm <= 0f) return Result.Failure("Height must be positive", Codes.INVALID_HEIGHT)
        val heightM = heightCm / CM_PER_M
        return Result.Success(weightKg / (heightM * heightM))
    }

    /** Classify a BMI value into a [BmiStatus]. Delegates to [BodyCompositionAssessment]. */
    fun assessBmi(bmi: Float): BmiStatus = BodyCompositionAssessment.assessBmi(bmi).status

    /** Classify a blood pressure reading using the app's inclusive component-wise status ladder. */
    fun assessBloodPressure(
        systolic: Int,
        diastolic: Int,
    ): BloodPressureStatus =
        when {
            assessSystolic(systolic) == MetricStatus.POOR || assessDiastolic(diastolic) == MetricStatus.POOR ->
                BloodPressureStatus.HypertensionStage2
            assessSystolic(systolic) == MetricStatus.WARNING || assessDiastolic(diastolic) == MetricStatus.WARNING ->
                BloodPressureStatus.HypertensionStage1
            assessSystolic(systolic) == MetricStatus.NEUTRAL || assessDiastolic(diastolic) == MetricStatus.NEUTRAL ->
                BloodPressureStatus.Neutral
            else -> BloodPressureStatus.Optimal
        }

    /** Classifies a systolic component using the inclusive blood-pressure ladder. */
    fun assessSystolic(systolic: Int?): MetricStatus =
        assessBloodPressureComponent(systolic, BP_NORMAL_SYS, BP_ELEVATED_SYS, BP_STAGE1_SYS_MAX)

    /** Classifies a diastolic component using the inclusive blood-pressure ladder. */
    fun assessDiastolic(diastolic: Int?): MetricStatus =
        assessBloodPressureComponent(diastolic, BP_NORMAL_DIA, BP_ELEVATED_DIA, BP_STAGE1_DIA_MAX)

    /** Chart metadata for systolic pressure, derived from the same constants as classification. */
    fun systolicReferenceBands(): List<ZoneBand> =
        bloodPressureReferenceBands(BP_NORMAL_SYS, BP_ELEVATED_SYS, BP_STAGE1_SYS_MAX)

    /** Chart metadata for diastolic pressure, derived from the same constants as classification. */
    fun diastolicReferenceBands(): List<ZoneBand> =
        bloodPressureReferenceBands(BP_NORMAL_DIA, BP_ELEVATED_DIA, BP_STAGE1_DIA_MAX)

    /**
     * Classify body-fat percentage by physiology profile and gender.
     * Delegates to [BodyCompositionAssessment]. `null` gender uses the fixed reference band.
     */
    fun assessBodyFatPercent(
        bodyFatPercent: Float,
        physiologyProfile: PhysiologyProfile,
        gender: Gender?,
    ): BodyFatStatus = BodyCompositionAssessment.assessBodyFat(bodyFatPercent, physiologyProfile, gender).status

    /**
     * Daily average of systolic / diastolic readings.
     * Returns [Result.Failure] if either list is empty.
     */
    fun calculateDailyBpAverage(
        systolics: List<Int>,
        diastolics: List<Int>,
    ): Result<Pair<Int, Int>> {
        if (systolics.isEmpty() || diastolics.isEmpty()) {
            return Result.Failure("Empty blood pressure series", Codes.EMPTY_SERIES)
        }
        return Result.Success(
            Pair(systolics.average().toInt(), diastolics.average().toInt()),
        )
    }

    /** Mean of a list of integer values, or [Result.Failure] if empty. */
    fun mean(values: List<Int>): Result<Double> =
        if (values.isEmpty()) {
            Result.Failure("Cannot compute mean of empty list", Codes.EMPTY_SERIES)
        } else {
            Result.Success(values.average())
        }

    /** Median of a list of integer values, or [Result.Failure] if empty. */
    fun median(values: List<Int>): Result<Double> {
        if (values.isEmpty()) return Result.Failure("Cannot compute median of empty list", Codes.EMPTY_SERIES)
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return Result.Success(
            if (sorted.size % 2 == 0) {
                (sorted[mid - 1] + sorted[mid]) / 2.0
            } else {
                sorted[mid].toDouble()
            },
        )
    }

    private fun assessBloodPressureComponent(
        value: Int?,
        optimalMax: Int,
        neutralMax: Int,
        warningMax: Int,
    ): MetricStatus =
        when {
            value == null -> MetricStatus.CALIBRATING
            value <= optimalMax -> MetricStatus.OPTIMAL
            value <= neutralMax -> MetricStatus.NEUTRAL
            value <= warningMax -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }

    private fun bloodPressureReferenceBands(
        optimalMax: Int,
        neutralMax: Int,
        warningMax: Int,
    ): List<ZoneBand> =
        listOf(
            ZoneBand(Double.NEGATIVE_INFINITY, optimalMax.toDouble(), HealthZone.OPTIMAL),
            ZoneBand(optimalMax.toDouble(), (neutralMax + 1).toDouble(), HealthZone.NEUTRAL),
            ZoneBand((neutralMax + 1).toDouble(), (warningMax + 1).toDouble(), HealthZone.WARNING),
            ZoneBand((warningMax + 1).toDouble(), Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
        )

    /** Stable [Result.Failure.code] values produced by this service. */
    object Codes {
        const val INVALID_WEIGHT: String = "INVALID_WEIGHT"
        const val INVALID_HEIGHT: String = "INVALID_HEIGHT"
        const val EMPTY_SERIES: String = "EMPTY_SERIES"
    }

    companion object {
        const val BP_NORMAL_SYS: Int = 120
        const val BP_NORMAL_DIA: Int = 80
        const val BP_ELEVATED_SYS: Int = 129
        const val BP_ELEVATED_DIA: Int = 89
        const val BP_STAGE1_SYS_MAX: Int = 139
        const val BP_STAGE1_DIA_MAX: Int = 99

        private const val CM_PER_M: Float = 100f
    }
}
