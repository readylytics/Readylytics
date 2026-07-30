package app.readylytics.health.domain.model

import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.preferences.PhysiologyProfile
import org.junit.Test
import kotlin.test.assertEquals

class BodyCompositionAssessmentTest {
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
