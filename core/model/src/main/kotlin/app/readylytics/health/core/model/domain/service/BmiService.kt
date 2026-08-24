package app.readylytics.health.core.model.domain.service

import app.readylytics.health.core.model.domain.model.BmiStatus
import app.readylytics.health.core.model.domain.model.BodyCompositionAssessment
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import javax.inject.Inject

/**
 * Computed BMI payload returned on a successful calculation.
 *
 * Carried inside a [Result.Success]; failure cases are surfaced via [Result.Failure] codes
 * defined in [BmiService.Codes].
 */
data class BmiData(
    val bmi: Float,
    val status: BmiStatus,
)

/**
 * Pure-Kotlin service for BMI calculation and classification.
 *
 * Constructor-injectable; no Android dependencies.
 * Returns [Result]s instead of throwing for invalid input — failure codes are stable strings
 * defined in [Codes] so callers can branch on them without depending on enum identities.
 */
class BmiService
    @Inject
    constructor() {
        /**
         * Compute BMI from a metric or imperial input, plus its classification.
         *
         * Imperial inputs (weight in lbs, height in inches) are converted to metric internally.
         */
        fun calculateBmi(
            weight: Float,
            height: Float,
            units: UnitSystem,
        ): Result<BmiData> =
            when {
                weight <= 0f -> Result.Failure("Weight must be positive", Codes.WEIGHT_NOT_POSITIVE)
                height <= 0f -> Result.Failure("Height must be positive", Codes.HEIGHT_NOT_POSITIVE)
                weight > MAX_WEIGHT -> Result.Failure("Weight exceeds maximum", Codes.WEIGHT_TOO_HIGH)
                height > MAX_HEIGHT -> Result.Failure("Height exceeds maximum", Codes.HEIGHT_TOO_HIGH)
                else -> {
                    val weightKg =
                        when (units) {
                            UnitSystem.METRIC -> weight
                            UnitSystem.IMPERIAL -> weight * LBS_TO_KG
                        }
                    val heightCm =
                        when (units) {
                            UnitSystem.METRIC -> height
                            UnitSystem.IMPERIAL -> height * INCHES_TO_CM
                        }
                    val heightM = heightCm / CM_PER_M
                    val bmi = weightKg / (heightM * heightM)
                    Result.Success(BmiData(bmi = bmi, status = classify(bmi)))
                }
            }

        /** Classify a BMI value into a [BmiStatus] band. Delegates to [BodyCompositionAssessment]. */
        fun classify(bmi: Float): BmiStatus = BodyCompositionAssessment.assessBmi(bmi).status

        /** Stable [Result.Failure.code] values produced by [calculateBmi]. */
        object Codes {
            const val WEIGHT_NOT_POSITIVE: String = "WEIGHT_NOT_POSITIVE"
            const val HEIGHT_NOT_POSITIVE: String = "HEIGHT_NOT_POSITIVE"
            const val WEIGHT_TOO_HIGH: String = "WEIGHT_TOO_HIGH"
            const val HEIGHT_TOO_HIGH: String = "HEIGHT_TOO_HIGH"
        }

        companion object {
            // Sanity limits (no humans outside these ranges).
            private const val MAX_WEIGHT: Float = 1_000f
            private const val MAX_HEIGHT: Float = 300f

            private const val LBS_TO_KG: Float = 0.453592f
            private const val INCHES_TO_CM: Float = 2.54f
            private const val CM_PER_M: Float = 100f
        }
    }
