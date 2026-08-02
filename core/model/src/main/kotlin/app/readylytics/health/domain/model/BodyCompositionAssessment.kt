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

/** A canonical BMI classification band with an inclusive lower and exclusive upper boundary. */
data class BmiBand(
    val category: BmiCategory,
    val status: BmiStatus,
    val minimumInclusive: Float?,
    val maximumExclusive: Float?,
)

/** Axis anchors and canonical classification bands for rendering a BMI reference visualization. */
data class BmiReference(
    val axisMinimum: Float,
    val referenceMidpoint: Float,
    val axisMaximum: Float,
    val bands: List<BmiBand>,
)

/** Result of classifying a BMI value: its category plus the [BmiStatus] shown on cards. */
data class BmiAssessment(
    val category: BmiCategory,
    val status: BmiStatus,
    val reference: BmiReference,
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
    val bands: List<BodyFatBand>,
)

/** A canonical body-fat category band with explicit boundary inclusion. */
data class BodyFatBand(
    val category: BodyFatCategory,
    val status: BodyFatStatus,
    val minimumInclusive: Float?,
    val maximumExclusive: Float?,
    val includesMinimum: Boolean = true,
    val includesMaximum: Boolean = false,
) {
    internal fun contains(value: Float): Boolean {
        val aboveMinimum =
            minimumInclusive == null ||
                if (includesMinimum) value >= minimumInclusive else value > minimumInclusive
        val belowMaximum =
            maximumExclusive == null ||
                if (includesMaximum) value <= maximumExclusive else value < maximumExclusive
        return aboveMinimum && belowMaximum
    }
}

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
    private const val BMI_AXIS_MINIMUM = 15f
    private const val BMI_REFERENCE_MIDPOINT = 21.7f
    private const val BMI_AXIS_MAXIMUM = 35f
    private const val BMI_HEALTHY_MINIMUM = 18.5f
    private const val BMI_OVERWEIGHT_MINIMUM = 25f
    private const val BMI_OBESITY_MINIMUM = 30f
    private const val MALE_ESSENTIAL_MIN = 2f
    private const val MALE_ATHLETIC_MIN = 6f
    private const val MALE_FITNESS_MIN = 14f
    private const val MALE_ACCEPTABLE_MIN = 18f
    private const val MALE_OBESE_MIN = 25f
    private const val FEMALE_ESSENTIAL_MIN = 10f
    private const val FEMALE_ATHLETIC_MIN = 14f
    private const val FEMALE_FITNESS_MIN = 21f
    private const val FEMALE_ACCEPTABLE_MIN = 25f
    private const val FEMALE_OBESE_MIN = 32f
    private const val FIXED_REFERENCE_MIN = 10f
    private const val FIXED_REFERENCE_MAX = 30f
    private const val FIXED_MIDPOINT = 20f

    /** Canonical BMI reference bands and visual-only axis anchors. */
    val bmiReference =
        BmiReference(
            axisMinimum = BMI_AXIS_MINIMUM,
            referenceMidpoint = BMI_REFERENCE_MIDPOINT,
            axisMaximum = BMI_AXIS_MAXIMUM,
            bands =
                listOf(
                    BmiBand(BmiCategory.UNDERWEIGHT, BmiStatus.Warning, null, BMI_HEALTHY_MINIMUM),
                    BmiBand(
                        BmiCategory.HEALTHY_WEIGHT,
                        BmiStatus.Optimal,
                        BMI_HEALTHY_MINIMUM,
                        BMI_OVERWEIGHT_MINIMUM,
                    ),
                    BmiBand(
                        BmiCategory.OVERWEIGHT,
                        BmiStatus.Warning,
                        BMI_OVERWEIGHT_MINIMUM,
                        BMI_OBESITY_MINIMUM,
                    ),
                    BmiBand(BmiCategory.OBESITY, BmiStatus.Poor, BMI_OBESITY_MINIMUM, null),
                ),
        )

    /** Classify a BMI value using the canonical WHO-aligned reference bands. */
    fun assessBmi(bmi: Float): BmiAssessment {
        val band =
            bmiReference.bands.first { band ->
                (band.minimumInclusive == null || bmi >= band.minimumInclusive) &&
                    (band.maximumExclusive == null || bmi < band.maximumExclusive)
            }
        return BmiAssessment(band.category, band.status, bmiReference)
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
        val reference = bodyFatReference(physiologyProfile, gender)
        val band = reference.bands.first { it.contains(bodyFatPercent) }
        return BodyFatAssessment(
            category = band.category,
            status = band.status,
            reference = reference,
        )
    }

    /** Canonical category bands plus visual-only axis and midpoint metadata. */
    fun bodyFatReference(
        physiologyProfile: PhysiologyProfile,
        gender: Gender?,
    ): BodyFatReference {
        val (axisMinimum, axisMaximum, bands) =
            when (gender) {
                Gender.MALE -> Triple(MALE_ESSENTIAL_MIN, MALE_OBESE_MIN, maleBands)
                Gender.FEMALE -> Triple(FEMALE_ESSENTIAL_MIN, FEMALE_OBESE_MIN, femaleBands)
                Gender.OTHER, Gender.PREFER_NOT_TO_SAY, null ->
                    Triple(FIXED_REFERENCE_MIN, FIXED_REFERENCE_MAX, fixedReferenceBands)
            }
        return BodyFatReference(
            axisMinimum = axisMinimum,
            referenceMidpoint = midpoint(physiologyProfile, gender),
            axisMaximum = axisMaximum,
            bands = bands,
        )
    }

    private val maleBands =
        listOf(
            BodyFatBand(BodyFatCategory.BELOW_ESSENTIAL, BodyFatStatus.Warning, null, MALE_ESSENTIAL_MIN),
            BodyFatBand(BodyFatCategory.ESSENTIAL, BodyFatStatus.Neutral, MALE_ESSENTIAL_MIN, MALE_ATHLETIC_MIN),
            BodyFatBand(BodyFatCategory.ATHLETIC, BodyFatStatus.Optimal, MALE_ATHLETIC_MIN, MALE_FITNESS_MIN),
            BodyFatBand(BodyFatCategory.FITNESS, BodyFatStatus.Optimal, MALE_FITNESS_MIN, MALE_ACCEPTABLE_MIN),
            BodyFatBand(BodyFatCategory.ACCEPTABLE, BodyFatStatus.Neutral, MALE_ACCEPTABLE_MIN, MALE_OBESE_MIN),
            BodyFatBand(BodyFatCategory.OBESE, BodyFatStatus.Poor, MALE_OBESE_MIN, null),
        )

    private val femaleBands =
        listOf(
            BodyFatBand(BodyFatCategory.BELOW_ESSENTIAL, BodyFatStatus.Warning, null, FEMALE_ESSENTIAL_MIN),
            BodyFatBand(
                BodyFatCategory.ESSENTIAL,
                BodyFatStatus.Neutral,
                FEMALE_ESSENTIAL_MIN,
                FEMALE_ATHLETIC_MIN,
            ),
            BodyFatBand(BodyFatCategory.ATHLETIC, BodyFatStatus.Optimal, FEMALE_ATHLETIC_MIN, FEMALE_FITNESS_MIN),
            BodyFatBand(BodyFatCategory.FITNESS, BodyFatStatus.Optimal, FEMALE_FITNESS_MIN, FEMALE_ACCEPTABLE_MIN),
            BodyFatBand(BodyFatCategory.ACCEPTABLE, BodyFatStatus.Neutral, FEMALE_ACCEPTABLE_MIN, FEMALE_OBESE_MIN),
            BodyFatBand(BodyFatCategory.OBESE, BodyFatStatus.Poor, FEMALE_OBESE_MIN, null),
        )

    /**
     * Fixed reference band for [Gender.OTHER], [Gender.PREFER_NOT_TO_SAY], or unset gender.
     * At or below [minimum] is [BodyFatCategory.BELOW_REFERENCE] (informational, not dangerously
     * low like [BodyFatCategory.BELOW_ESSENTIAL]); above [maximum] is
     * [BodyFatCategory.ABOVE_REFERENCE]; the open interval between is
     * [BodyFatCategory.WITHIN_REFERENCE].
     */
    private val fixedReferenceBands =
        listOf(
            BodyFatBand(
                BodyFatCategory.BELOW_REFERENCE,
                BodyFatStatus.Neutral,
                null,
                FIXED_REFERENCE_MIN,
                includesMaximum = true,
            ),
            BodyFatBand(
                BodyFatCategory.WITHIN_REFERENCE,
                BodyFatStatus.Optimal,
                FIXED_REFERENCE_MIN,
                FIXED_REFERENCE_MAX,
                includesMinimum = false,
                includesMaximum = true,
            ),
            BodyFatBand(
                BodyFatCategory.ABOVE_REFERENCE,
                BodyFatStatus.Poor,
                FIXED_REFERENCE_MAX,
                null,
                includesMinimum = false,
            ),
        )

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
    }
