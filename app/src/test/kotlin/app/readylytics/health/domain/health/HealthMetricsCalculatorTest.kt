package app.readylytics.health.domain.health

import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.domain.calculation.HealthMetricsCalculator
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyFatStatus
import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.01f

// ─── calculateBmi ─────────────────────────────────────────────────────────────
class CalculateBmiTest {
    @Test
    fun `typical healthy male returns correct bmi`() {
        // 70 kg / (1.75m)^2 = 22.857
        val bmi = HealthMetricsCalculator.calculateBmi(70f, 175f)
        assertEquals(22.86f, bmi, DELTA)
    }

    @Test
    fun `overweight person returns correct bmi`() {
        // 90 kg / (1.70m)^2 = 31.14
        val bmi = HealthMetricsCalculator.calculateBmi(90f, 170f)
        assertEquals(31.14f, bmi, DELTA)
    }

    @Test
    fun `low weight returns correct bmi`() {
        // 50 kg / (1.80m)^2 = 15.43
        val bmi = HealthMetricsCalculator.calculateBmi(50f, 180f)
        assertEquals(15.43f, bmi, DELTA)
    }

    @Test
    fun `round numbers produce expected bmi`() {
        val bmi = HealthMetricsCalculator.calculateBmi(100f, 200f)
        assertEquals(25.0f, bmi, DELTA)
    }

    @Test
    fun `small body dimensions produce correct bmi`() {
        val bmi = HealthMetricsCalculator.calculateBmi(30f, 150f)
        assertEquals(13.33f, bmi, DELTA)
    }

    @Test
    fun `height conversion from cm to m is applied correctly`() {
        // 80 kg / (1.60m)^2 = 31.25
        val bmi = HealthMetricsCalculator.calculateBmi(80f, 160f)
        assertEquals(31.25f, bmi, DELTA)
    }
}

// ─── assessBmi ────────────────────────────────────────────────────────────────
class AssessBmiTest {
    @Test
    fun `bmi boundary 18 point 49 is Warning (underweight)`() {
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(18.49f))
    }

    @Test
    fun `bmi boundary 18 point 5 is Optimal`() {
        assertEquals(BmiStatus.Optimal, HealthMetricsCalculator.assessBmi(18.5f))
    }

    @Test
    fun `bmi below 25 is Optimal`() {
        assertEquals(BmiStatus.Optimal, HealthMetricsCalculator.assessBmi(24.9f))
    }

    @Test
    fun `bmi exactly 25 is Warning`() {
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(25.0f))
    }

    @Test
    fun `bmi 27 is Warning`() {
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(27f))
    }

    @Test
    fun `bmi exactly 30 is Poor`() {
        assertEquals(BmiStatus.Poor, HealthMetricsCalculator.assessBmi(30.0f))
    }

    @Test
    fun `bmi 32 is Poor`() {
        assertEquals(BmiStatus.Poor, HealthMetricsCalculator.assessBmi(32f))
    }

    @Test
    fun `bmi exactly 35 is Poor`() {
        assertEquals(BmiStatus.Poor, HealthMetricsCalculator.assessBmi(35.0f))
    }

    @Test
    fun `bmi 40 is Poor`() {
        assertEquals(BmiStatus.Poor, HealthMetricsCalculator.assessBmi(40f))
    }

    @Test
    fun `bmi zero is Warning (underweight)`() {
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(0f))
    }

    @Test
    fun `very low bmi is Warning (underweight)`() {
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(10f))
    }

    @Test
    fun `bmi boundary 29 point 9 is Warning`() =
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(29.9f))

    @Test
    fun `bmi boundary 34 point 9 is Poor`() = assertEquals(BmiStatus.Poor, HealthMetricsCalculator.assessBmi(34.9f))
}

// ─── assessBloodPressure ──────────────────────────────────────────────────────
class AssessBloodPressureTest {
    @Test
    fun `systolic below 120 and diastolic below 80 is Optimal`() =
        assertEquals(BloodPressureStatus.Optimal, HealthMetricsCalculator.assessBloodPressure(110, 70))

    @Test
    fun `systolic 119 and diastolic 79 is Optimal`() =
        assertEquals(BloodPressureStatus.Optimal, HealthMetricsCalculator.assessBloodPressure(119, 79))

    @Test
    fun `systolic 120 diastolic 75 is Optimal`() =
        assertEquals(BloodPressureStatus.Optimal, HealthMetricsCalculator.assessBloodPressure(120, 75))

    @Test
    fun `systolic 120 diastolic 80 is Optimal`() =
        assertEquals(BloodPressureStatus.Optimal, HealthMetricsCalculator.assessBloodPressure(120, 80))

    @Test
    fun `systolic 121 diastolic 75 is Neutral`() =
        assertEquals(BloodPressureStatus.Neutral, HealthMetricsCalculator.assessBloodPressure(121, 75))

    @Test
    fun `systolic 129 diastolic 79 is Neutral`() =
        assertEquals(BloodPressureStatus.Neutral, HealthMetricsCalculator.assessBloodPressure(129, 79))

    @Test
    fun `systolic 130 diastolic 75 is HypertensionStage1`() =
        assertEquals(BloodPressureStatus.HypertensionStage1, HealthMetricsCalculator.assessBloodPressure(130, 75))

    @Test
    fun `systolic 139 diastolic 85 is HypertensionStage1`() =
        assertEquals(BloodPressureStatus.HypertensionStage1, HealthMetricsCalculator.assessBloodPressure(139, 85))

    @Test
    fun `diastolic 80 with normal systolic is Optimal`() =
        assertEquals(BloodPressureStatus.Optimal, HealthMetricsCalculator.assessBloodPressure(115, 80))

    @Test
    fun `diastolic 89 with normal systolic is Neutral`() =
        assertEquals(BloodPressureStatus.Neutral, HealthMetricsCalculator.assessBloodPressure(115, 89))

    @Test
    fun `systolic 140 is HypertensionStage2`() =
        assertEquals(BloodPressureStatus.HypertensionStage2, HealthMetricsCalculator.assessBloodPressure(140, 70))

    @Test
    fun `diastolic 90 with normal systolic is HypertensionStage1`() =
        assertEquals(BloodPressureStatus.HypertensionStage1, HealthMetricsCalculator.assessBloodPressure(115, 90))

    @Test
    fun `severe hypertension is HypertensionStage2`() =
        assertEquals(BloodPressureStatus.HypertensionStage2, HealthMetricsCalculator.assessBloodPressure(180, 110))
}

// ─── assessBodyFatPercent ─────────────────────────────────────────────────────
// Canonical, continuous bands via BodyCompositionAssessment; status is independent of
// physiologyProfile (profile only shifts the gauge reference midpoint, covered by
// BodyCompositionAssessmentTest).
class AssessBodyFatPercentTest {
    @Test
    fun `null gender uses fixed reference band`() =
        assertEquals(
            BodyFatStatus.Optimal,
            HealthMetricsCalculator.assessBodyFatPercent(20f, PhysiologyProfile.ACTIVE, null),
        )

    @Test
    fun `male below essential is Warning`() =
        assertEquals(
            BodyFatStatus.Warning,
            HealthMetricsCalculator.assessBodyFatPercent(1.99f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun `male at boundary 2 is Neutral`() =
        assertEquals(
            BodyFatStatus.Neutral,
            HealthMetricsCalculator.assessBodyFatPercent(2f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun `male athletic is Optimal`() =
        assertEquals(
            BodyFatStatus.Optimal,
            HealthMetricsCalculator.assessBodyFatPercent(6f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun `male acceptable range is Neutral`() =
        assertEquals(
            BodyFatStatus.Neutral,
            HealthMetricsCalculator.assessBodyFatPercent(18f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun `male obese is Poor`() =
        assertEquals(
            BodyFatStatus.Poor,
            HealthMetricsCalculator.assessBodyFatPercent(25f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun `female below essential is Warning`() =
        assertEquals(
            BodyFatStatus.Warning,
            HealthMetricsCalculator.assessBodyFatPercent(9.99f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun `female at boundary 10 is Neutral`() =
        assertEquals(
            BodyFatStatus.Neutral,
            HealthMetricsCalculator.assessBodyFatPercent(10f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun `female athletic is Optimal`() =
        assertEquals(
            BodyFatStatus.Optimal,
            HealthMetricsCalculator.assessBodyFatPercent(14f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun `female acceptable range is Neutral`() =
        assertEquals(
            BodyFatStatus.Neutral,
            HealthMetricsCalculator.assessBodyFatPercent(25f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun `female obese is Poor`() =
        assertEquals(
            BodyFatStatus.Poor,
            HealthMetricsCalculator.assessBodyFatPercent(32f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun `OTHER gender within fixed band is Optimal`() =
        assertEquals(
            BodyFatStatus.Optimal,
            HealthMetricsCalculator.assessBodyFatPercent(20f, PhysiologyProfile.SEDENTARY, Gender.OTHER),
        )

    @Test
    fun `OTHER gender at fixed minimum is Neutral`() =
        assertEquals(
            BodyFatStatus.Neutral,
            HealthMetricsCalculator.assessBodyFatPercent(10f, PhysiologyProfile.SEDENTARY, Gender.OTHER),
        )

    @Test
    fun `OTHER gender above fixed maximum is Poor`() =
        assertEquals(
            BodyFatStatus.Poor,
            HealthMetricsCalculator.assessBodyFatPercent(30.01f, PhysiologyProfile.SEDENTARY, Gender.OTHER),
        )

    @Test
    fun `PREFER_NOT_TO_SAY gender within fixed band is Optimal`() {
        assertEquals(
            BodyFatStatus.Optimal,
            HealthMetricsCalculator.assessBodyFatPercent(20f, PhysiologyProfile.ATHLETE, Gender.PREFER_NOT_TO_SAY),
        )
    }

    @Test
    fun `PREFER_NOT_TO_SAY gender above fixed maximum is Poor`() {
        assertEquals(
            BodyFatStatus.Poor,
            HealthMetricsCalculator.assessBodyFatPercent(30.5f, PhysiologyProfile.ATHLETE, Gender.PREFER_NOT_TO_SAY),
        )
    }

    @Test
    fun `zero body fat is below essential Warning for male`() =
        assertEquals(
            BodyFatStatus.Warning,
            HealthMetricsCalculator.assessBodyFatPercent(0f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun `extremely high body fat is Poor`() =
        assertEquals(
            BodyFatStatus.Poor,
            HealthMetricsCalculator.assessBodyFatPercent(60f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun `male status is independent of physiology profile`() {
        assertEquals(
            HealthMetricsCalculator.assessBodyFatPercent(19f, PhysiologyProfile.ATHLETE, Gender.MALE),
            HealthMetricsCalculator.assessBodyFatPercent(19f, PhysiologyProfile.SEDENTARY, Gender.MALE),
        )
    }
}

// ─── calculateDailyBpAverage ──────────────────────────────────────────────────
class CalculateDailyBpAverageTest {
    @Test
    fun `empty lists return 0,0`() =
        assertEquals(Pair(0, 0), HealthMetricsCalculator.calculateDailyBpAverage(emptyList(), emptyList()))

    @Test
    fun `empty systolics returns 0,0`() =
        assertEquals(Pair(0, 0), HealthMetricsCalculator.calculateDailyBpAverage(emptyList(), listOf(80)))

    @Test
    fun `empty diastolics returns 0,0`() =
        assertEquals(Pair(0, 0), HealthMetricsCalculator.calculateDailyBpAverage(listOf(120), emptyList()))

    @Test
    fun `single reading returns same value`() =
        assertEquals(Pair(120, 80), HealthMetricsCalculator.calculateDailyBpAverage(listOf(120), listOf(80)))

    @Test
    fun `two identical readings average to same value`() =
        assertEquals(Pair(120, 80), HealthMetricsCalculator.calculateDailyBpAverage(listOf(120, 120), listOf(80, 80)))

    @Test
    fun `two different readings average correctly`() =
        assertEquals(Pair(115, 75), HealthMetricsCalculator.calculateDailyBpAverage(listOf(110, 120), listOf(70, 80)))

    @Test
    fun `three readings produce correct average`() {
        val systolics = listOf(110, 120, 130)
        val diastolics = listOf(70, 75, 80)
        assertEquals(Pair(120, 75), HealthMetricsCalculator.calculateDailyBpAverage(systolics, diastolics))
    }

    @Test
    fun `average truncates fractional part to int`() {
        // (110 + 121) / 2 = 115.5 → truncated to 115
        val result = HealthMetricsCalculator.calculateDailyBpAverage(listOf(110, 121), listOf(70, 71))
        assertEquals(115, result.first)
        assertEquals(70, result.second)
    }

    @Test
    fun `large uniform set produces same value`() {
        val systolics = List(100) { 120 }
        val diastolics = List(100) { 80 }
        assertEquals(Pair(120, 80), HealthMetricsCalculator.calculateDailyBpAverage(systolics, diastolics))
    }

    @Test
    fun `extreme high values average correctly`() {
        assertEquals(
            Pair(210, 120),
            HealthMetricsCalculator.calculateDailyBpAverage(listOf(200, 220), listOf(110, 130)),
        )
    }

    @Test
    fun `low boundary values average correctly`() {
        assertEquals(
            Pair(80, 50),
            HealthMetricsCalculator.calculateDailyBpAverage(listOf(80, 80), listOf(50, 50)),
        )
    }
}
