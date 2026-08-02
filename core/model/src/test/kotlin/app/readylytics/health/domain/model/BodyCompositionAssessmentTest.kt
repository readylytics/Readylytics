package app.readylytics.health.domain.model

import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.preferences.PhysiologyProfile
import org.junit.Test
import kotlin.test.assertEquals

class BodyCompositionAssessmentTest {
    @Test
    fun `bmi reference exposes ordered canonical bands and visual-only axis anchors`() {
        val reference = BodyCompositionAssessment.bmiReference

        assertEquals(15f, reference.axisMinimum)
        assertEquals(21.7f, reference.referenceMidpoint)
        assertEquals(35f, reference.axisMaximum)
        assertEquals(
            listOf(
                BmiBand(BmiCategory.UNDERWEIGHT, BmiStatus.Warning, null, 18.5f),
                BmiBand(BmiCategory.HEALTHY_WEIGHT, BmiStatus.Optimal, 18.5f, 25f),
                BmiBand(BmiCategory.OVERWEIGHT, BmiStatus.Warning, 25f, 30f),
                BmiBand(BmiCategory.OBESITY, BmiStatus.Poor, 30f, null),
            ),
            reference.bands,
        )
    }

    @Test
    fun `bmi assessments select the matching canonical band at every boundary`() {
        val expected = listOf(
            18.4f to BmiStatus.Warning,
            18.5f to BmiStatus.Optimal,
            24.9f to BmiStatus.Optimal,
            25f to BmiStatus.Warning,
            29.9f to BmiStatus.Warning,
            30f to BmiStatus.Poor,
        )

        expected.forEach { (bmi, status) ->
            val assessment = BodyCompositionAssessment.assessBmi(bmi)
            assertEquals(status, assessment.status, "BMI $bmi")
            assertEquals(BodyCompositionAssessment.bmiReference, assessment.reference)
        }
    }

    @Test
    fun `body fat reference exposes the canonical female category bands`() {
        val reference =
            BodyCompositionAssessment.bodyFatReference(
                physiologyProfile = PhysiologyProfile.ACTIVE,
                gender = Gender.FEMALE,
            )

        assertEquals(
            listOf(
                BodyFatBand(BodyFatCategory.BELOW_ESSENTIAL, BodyFatStatus.Warning, null, 10f),
                BodyFatBand(BodyFatCategory.ESSENTIAL, BodyFatStatus.Neutral, 10f, 14f),
                BodyFatBand(BodyFatCategory.ATHLETIC, BodyFatStatus.Optimal, 14f, 21f),
                BodyFatBand(BodyFatCategory.FITNESS, BodyFatStatus.Optimal, 21f, 25f),
                BodyFatBand(BodyFatCategory.ACCEPTABLE, BodyFatStatus.Neutral, 25f, 32f),
                BodyFatBand(BodyFatCategory.OBESE, BodyFatStatus.Poor, 32f, null),
            ),
            reference.bands,
        )
        assertEquals(
            BodyFatStatus.Neutral,
            reference.bands
                .first {
                    (it.minimumInclusive == null || 10f >= it.minimumInclusive) &&
                        (it.maximumExclusive == null || 10f < it.maximumExclusive)
                }.status,
        )
    }

    @Test
    fun `bmi boundaries map to approved categories and statuses`() {
        assertEquals(BmiCategory.UNDERWEIGHT, BodyCompositionAssessment.assessBmi(18.49f).category)
        assertEquals(BmiStatus.Warning, BodyCompositionAssessment.assessBmi(18.49f).status)
        assertEquals(BmiCategory.HEALTHY_WEIGHT, BodyCompositionAssessment.assessBmi(18.5f).category)
        assertEquals(BmiStatus.Optimal, BodyCompositionAssessment.assessBmi(24.9f).status)
        assertEquals(BmiStatus.Warning, BodyCompositionAssessment.assessBmi(25f).status)
        assertEquals(BmiStatus.Poor, BodyCompositionAssessment.assessBmi(30f).status)
    }

    @Test
    fun `male body fat continuous boundaries are explicit`() {
        val profile = PhysiologyProfile.ACTIVE
        assertEquals(BodyFatStatus.Warning, BodyCompositionAssessment.assessBodyFat(1.99f, profile, Gender.MALE).status)
        assertEquals(BodyFatStatus.Neutral, BodyCompositionAssessment.assessBodyFat(2f, profile, Gender.MALE).status)
        assertEquals(BodyFatStatus.Optimal, BodyCompositionAssessment.assessBodyFat(6f, profile, Gender.MALE).status)
        assertEquals(BodyFatStatus.Neutral, BodyCompositionAssessment.assessBodyFat(18f, profile, Gender.MALE).status)
        assertEquals(BodyFatStatus.Poor, BodyCompositionAssessment.assessBodyFat(25f, profile, Gender.MALE).status)
    }

    @Test
    fun `fixed group uses agreed midpoint and bands`() {
        val result = BodyCompositionAssessment.assessBodyFat(20f, PhysiologyProfile.ATHLETE, null)
        assertEquals(20f, result.reference.referenceMidpoint)
        assertEquals(BodyFatStatus.Optimal, result.status)
        assertEquals(
            BodyFatStatus.Neutral,
            BodyCompositionAssessment.assessBodyFat(10f, PhysiologyProfile.ACTIVE, null).status,
        )
        assertEquals(
            BodyFatStatus.Poor,
            BodyCompositionAssessment.assessBodyFat(30.01f, PhysiologyProfile.ACTIVE, null).status,
        )
    }
}
