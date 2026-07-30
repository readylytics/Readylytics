package app.readylytics.health.domain.model

import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.preferences.PhysiologyProfile

/** BMI weight-status category derived from a computed BMI value. */
enum class BmiCategory {
    UNDERWEIGHT,
    HEALTHY_WEIGHT,
    OVERWEIGHT,
    OBESITY,
}

/**
 * Body-fat-percentage category.
 *
 * [BELOW_ESSENTIAL], [ESSENTIAL], [ATHLETIC], [FITNESS], [ACCEPTABLE], and [OBESE] apply when a
 * biological sex is known ([Gender.MALE] or [Gender.FEMALE]) and use continuous bands.
 * [BELOW_REFERENCE], [WITHIN_REFERENCE], and [ABOVE_REFERENCE] apply to the fixed reference band
 * used when gender is [Gender.OTHER], [Gender.PREFER_NOT_TO_SAY], or unset.
 */
enum class BodyFatCategory {
    BELOW_ESSENTIAL,
    ESSENTIAL,
    ATHLETIC,
    FITNESS,
    ACCEPTABLE,
    OBESE,
    BELOW_REFERENCE,
    WITHIN_REFERENCE,
    ABOVE_REFERENCE,
}

/** Result of classifying a BMI value: its category plus the [BmiStatus] shown on cards. */
data class BmiAssessment(
    val category: BmiCategory,
    val status: BmiStatus,
)

/**
 * Axis anchors for rendering a body-fat reference-range visualization.
 *
 * [axisMinimum] and [axisMaximum] bound the drawn scale; [referenceMidpoint] is the
 * profile/gender-specific target value shown as the reference marker. The marker is purely
 * informational — it never changes [BodyFatAssessment.status].
 */
data class BodyFatReference(
    val axisMinimum: Float,
    val referenceMidpoint: Float,
    val axisMaximum: Float,
)

/** Result of classifying a body-fat percentage: category, [BodyFatStatus], and gauge reference. */
data class BodyFatAssessment(
    val category: BodyFatCategory,
    val status: BodyFatStatus,
    val reference: BodyFatReference,
)

/**
 * Canonical, single-source-of-truth BMI and Body Fat classification.
 *
 * Pure Kotlin, zero Android dependencies. `BmiService`, `HealthMetricsService`, and
 * `HealthMetricsCalculator` all delegate here instead of duplicating threshold logic — see
 * `internal-docs/DATA_FLOW.md` for the canonical thresholds and `ABOUT.md` for the user-facing
 * explanation. These are the only domain-threshold changes approved for the dashboard
 * visualization-modes plan; scoring formulas elsewhere are untouched.
 */
object BodyCompositionAssessment {
    private const val MALE_ESSENTIAL_MIN = 2f
    private const val MALE_OBESE_MIN = 25f
    private const val FEMALE_ESSENTIAL_MIN = 10f
    private const val FEMALE_OBESE_MIN = 32f
    private const val FIXED_REFERENCE_MIN = 10f
    private const val FIXED_REFERENCE_MAX = 30f
    private const val FIXED_MIDPOINT = 20f

    /** Classify a BMI value using the WHO-aligned 18.5 / 25 / 30 thresholds. */
    fun assessBmi(bmi: Float): BmiAssessment =
        when {
            bmi < 18.5f -> BmiAssessment(BmiCategory.UNDERWEIGHT, BmiStatus.Warning)
            bmi < 25f -> BmiAssessment(BmiCategory.HEALTHY_WEIGHT, BmiStatus.Optimal)
            bmi < 30f -> BmiAssessment(BmiCategory.OVERWEIGHT, BmiStatus.Warning)
            else -> BmiAssessment(BmiCategory.OBESITY, BmiStatus.Poor)
        }

    /**
     * Classify a body-fat percentage.
     *
     * Male and female use continuous, gender-specific bands (status independent of
     * [physiologyProfile]). Any other or unset gender uses a fixed reference band centered on
     * [FIXED_MIDPOINT], also independent of [physiologyProfile]. [physiologyProfile] only shifts
     * the [BodyFatReference.referenceMidpoint] marker used for the gauge.
     */
    fun assessBodyFat(
        bodyFatPercent: Float,
        physiologyProfile: PhysiologyProfile,
        gender: Gender?,
    ): BodyFatAssessment {
        val (category, status) =
            when (gender) {
                Gender.MALE -> maleStatus(bodyFatPercent)
                Gender.FEMALE -> femaleStatus(bodyFatPercent)
                Gender.OTHER, Gender.PREFER_NOT_TO_SAY, null ->
                    fixedGroupStatus(bodyFatPercent, FIXED_REFERENCE_MIN, FIXED_REFERENCE_MAX)
            }
        val (axisMinimum, axisMaximum) =
            when (gender) {
                Gender.MALE -> MALE_ESSENTIAL_MIN to MALE_OBESE_MIN
                Gender.FEMALE -> FEMALE_ESSENTIAL_MIN to FEMALE_OBESE_MIN
                Gender.OTHER, Gender.PREFER_NOT_TO_SAY, null -> FIXED_REFERENCE_MIN to FIXED_REFERENCE_MAX
            }
        return BodyFatAssessment(
            category = category,
            status = status,
            reference =
                BodyFatReference(
                    axisMinimum = axisMinimum,
                    referenceMidpoint = midpoint(physiologyProfile, gender),
                    axisMaximum = axisMaximum,
                ),
        )
    }

    private fun maleStatus(value: Float): Pair<BodyFatCategory, BodyFatStatus> =
        when {
            value < 2f -> BodyFatCategory.BELOW_ESSENTIAL to BodyFatStatus.Warning
            value < 6f -> BodyFatCategory.ESSENTIAL to BodyFatStatus.Neutral
            value < 14f -> BodyFatCategory.ATHLETIC to BodyFatStatus.Optimal
            value < 18f -> BodyFatCategory.FITNESS to BodyFatStatus.Optimal
            value < 25f -> BodyFatCategory.ACCEPTABLE to BodyFatStatus.Neutral
            else -> BodyFatCategory.OBESE to BodyFatStatus.Poor
        }

    private fun femaleStatus(value: Float): Pair<BodyFatCategory, BodyFatStatus> =
        when {
            value < 10f -> BodyFatCategory.BELOW_ESSENTIAL to BodyFatStatus.Warning
            value < 14f -> BodyFatCategory.ESSENTIAL to BodyFatStatus.Neutral
            value < 21f -> BodyFatCategory.ATHLETIC to BodyFatStatus.Optimal
            value < 25f -> BodyFatCategory.FITNESS to BodyFatStatus.Optimal
            value < 32f -> BodyFatCategory.ACCEPTABLE to BodyFatStatus.Neutral
            else -> BodyFatCategory.OBESE to BodyFatStatus.Poor
        }

    /**
     * Fixed reference band for [Gender.OTHER], [Gender.PREFER_NOT_TO_SAY], or unset gender.
     * At or below [minimum] is [BodyFatCategory.BELOW_REFERENCE] (informational, not dangerously
     * low like [BodyFatCategory.BELOW_ESSENTIAL]); above [maximum] is
     * [BodyFatCategory.ABOVE_REFERENCE]; the open interval between is
     * [BodyFatCategory.WITHIN_REFERENCE].
     */
    private fun fixedGroupStatus(
        value: Float,
        minimum: Float,
        maximum: Float,
    ): Pair<BodyFatCategory, BodyFatStatus> =
        when {
            value <= minimum -> BodyFatCategory.BELOW_REFERENCE to BodyFatStatus.Neutral
            value > maximum -> BodyFatCategory.ABOVE_REFERENCE to BodyFatStatus.Poor
            else -> BodyFatCategory.WITHIN_REFERENCE to BodyFatStatus.Optimal
        }

    /**
     * Target body-fat value shown as the gauge reference marker. For male/female this varies by
     * [profile]; for any other or unset gender it is fixed at [FIXED_MIDPOINT] regardless of
     * [profile].
     */
    private fun midpoint(
        profile: PhysiologyProfile,
        gender: Gender?,
    ): Float =
        when (gender) {
            Gender.MALE ->
                when (profile) {
                    PhysiologyProfile.ATHLETE -> 9.5f
                    PhysiologyProfile.ACTIVE -> 15.5f
                    PhysiologyProfile.SEDENTARY -> 19.5f
                }
            Gender.FEMALE ->
                when (profile) {
                    PhysiologyProfile.ATHLETE -> 17f
                    PhysiologyProfile.ACTIVE -> 22.5f
                    PhysiologyProfile.SEDENTARY -> 26.5f
                }
            Gender.OTHER, Gender.PREFER_NOT_TO_SAY, null -> FIXED_MIDPOINT
        }
}

/** Maps a [BodyFatStatus] to the shared [MetricStatus] used by dashboard cards. */
fun BodyFatStatus.toMetricStatus(): MetricStatus =
    when (this) {
        BodyFatStatus.Optimal -> MetricStatus.OPTIMAL
        BodyFatStatus.Neutral -> MetricStatus.NEUTRAL
        BodyFatStatus.Warning -> MetricStatus.WARNING
        BodyFatStatus.Poor -> MetricStatus.POOR
        BodyFatStatus.Calibrating -> MetricStatus.CALIBRATING
    }
