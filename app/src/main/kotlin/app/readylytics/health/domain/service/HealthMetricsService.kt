package app.readylytics.health.domain.service

import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyFatStatus
import app.readylytics.health.domain.model.Result

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

    /** Classify a BMI value into a [BmiStatus]. */
    fun assessBmi(bmi: Float): BmiStatus =
        when {
            bmi < OVERWEIGHT_THRESHOLD -> BmiStatus.Optimal
            bmi < OBESE_CLASS_1_THRESHOLD -> BmiStatus.Neutral
            bmi < OBESE_CLASS_2_THRESHOLD -> BmiStatus.Warning
            else -> BmiStatus.Poor
        }

    /** Classify a blood pressure reading using ACC/AHA 2017 stages. */
    fun assessBloodPressure(
        systolic: Int,
        diastolic: Int,
    ): BloodPressureStatus =
        when {
            systolic < BP_NORMAL_SYS && diastolic < BP_NORMAL_DIA -> BloodPressureStatus.Optimal
            systolic <= BP_ELEVATED_SYS && diastolic < BP_NORMAL_DIA -> BloodPressureStatus.Neutral
            systolic in BP_STAGE1_SYS_RANGE || diastolic in BP_STAGE1_DIA_RANGE ->
                BloodPressureStatus.HypertensionStage1
            else -> BloodPressureStatus.HypertensionStage2
        }

    /** Classify body-fat percentage by gender and age. */
    fun assessBodyFatPercent(
        bodyFatPercent: Float,
        ageYears: Int,
        gender: Gender?,
    ): BodyFatStatus {
        if (gender == null) return BodyFatStatus.Calibrating
        val age = ageYears.coerceIn(MIN_AGE, MAX_AGE)
        val (optimalMax, neutralMax) = thresholdsFor(gender, age)
        return when {
            bodyFatPercent <= optimalMax -> BodyFatStatus.Optimal
            bodyFatPercent <= neutralMax -> BodyFatStatus.Neutral
            else -> BodyFatStatus.Poor
        }
    }

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

    private fun thresholdsFor(
        gender: Gender,
        age: Int,
    ): Pair<Float, Float> =
        when (gender) {
            Gender.MALE ->
                when (age) {
                    in 20..40 -> Pair(19f, 24f)
                    in 41..60 -> Pair(22f, 28f)
                    else -> Pair(24f, 30f)
                }
            Gender.FEMALE ->
                when (age) {
                    in 20..40 -> Pair(32f, 38f)
                    in 41..60 -> Pair(34f, 40f)
                    else -> Pair(36f, 42f)
                }
            Gender.OTHER, Gender.PREFER_NOT_TO_SAY -> Pair(25f, 35f)
        }

    /** Stable [Result.Failure.code] values produced by this service. */
    object Codes {
        const val INVALID_WEIGHT: String = "INVALID_WEIGHT"
        const val INVALID_HEIGHT: String = "INVALID_HEIGHT"
        const val EMPTY_SERIES: String = "EMPTY_SERIES"
    }

    companion object {
        const val OVERWEIGHT_THRESHOLD: Float = 25f
        const val OBESE_CLASS_1_THRESHOLD: Float = 30f
        const val OBESE_CLASS_2_THRESHOLD: Float = 35f

        const val BP_NORMAL_SYS: Int = 120
        const val BP_NORMAL_DIA: Int = 80
        const val BP_ELEVATED_SYS: Int = 129
        val BP_STAGE1_SYS_RANGE: IntRange = 130..139
        val BP_STAGE1_DIA_RANGE: IntRange = 80..89

        const val MIN_AGE: Int = 1
        const val MAX_AGE: Int = 120

        private const val CM_PER_M: Float = 100f
    }
}
