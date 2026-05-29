package com.gregor.lauritz.healthdashboard.domain.health

import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.domain.calculation.HealthMetricsCalculator
import com.gregor.lauritz.healthdashboard.domain.model.BloodPressureStatus
import com.gregor.lauritz.healthdashboard.domain.model.BmiStatus
import com.gregor.lauritz.healthdashboard.domain.model.BodyFatStatus
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
    fun `bmi below 25 is Optimal`() {
        assertEquals(BmiStatus.Optimal, HealthMetricsCalculator.assessBmi(24.9f))
    }

    @Test
    fun `bmi exactly 25 is Neutral`() {
        assertEquals(BmiStatus.Neutral, HealthMetricsCalculator.assessBmi(25.0f))
    }

    @Test
    fun `bmi 27 is Neutral`() {
        assertEquals(BmiStatus.Neutral, HealthMetricsCalculator.assessBmi(27f))
    }

    @Test
    fun `bmi exactly 30 is Warning`() {
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(30.0f))
    }

    @Test
    fun `bmi 32 is Warning`() {
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(32f))
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
    fun `bmi zero is Optimal`() {
        assertEquals(BmiStatus.Optimal, HealthMetricsCalculator.assessBmi(0f))
    }

    @Test
    fun `very low bmi is Optimal`() {
        assertEquals(BmiStatus.Optimal, HealthMetricsCalculator.assessBmi(10f))
    }

    @Test
    fun `bmi boundary 29 point 9 is Neutral`() =
        assertEquals(BmiStatus.Neutral, HealthMetricsCalculator.assessBmi(29.9f))

    @Test
    fun `bmi boundary 34 point 9 is Warning`() =
        assertEquals(BmiStatus.Warning, HealthMetricsCalculator.assessBmi(34.9f))
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
    fun `systolic 120 diastolic 75 is Neutral`() =
        assertEquals(BloodPressureStatus.Neutral, HealthMetricsCalculator.assessBloodPressure(120, 75))

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
    fun `diastolic 80 with normal systolic is HypertensionStage1`() =
        assertEquals(BloodPressureStatus.HypertensionStage1, HealthMetricsCalculator.assessBloodPressure(115, 80))

    @Test
    fun `diastolic 89 with normal systolic is HypertensionStage1`() =
        assertEquals(BloodPressureStatus.HypertensionStage1, HealthMetricsCalculator.assessBloodPressure(115, 89))

    @Test
    fun `systolic 140 is HypertensionStage2`() =
        assertEquals(BloodPressureStatus.HypertensionStage2, HealthMetricsCalculator.assessBloodPressure(140, 70))

    @Test
    fun `diastolic 90 is HypertensionStage2`() =
        assertEquals(BloodPressureStatus.HypertensionStage2, HealthMetricsCalculator.assessBloodPressure(115, 90))

    @Test
    fun `severe hypertension is HypertensionStage2`() =
        assertEquals(BloodPressureStatus.HypertensionStage2, HealthMetricsCalculator.assessBloodPressure(180, 110))
}

// ─── assessBodyFatPercent ─────────────────────────────────────────────────────
class AssessBodyFatPercentTest {
    @Test
    fun `null gender returns Calibrating`() =
        assertEquals(BodyFatStatus.Calibrating, HealthMetricsCalculator.assessBodyFatPercent(20f, 30, null))

    @Test
    fun `male age 25 low fat is Optimal`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(15f, 25, Gender.MALE))

    @Test
    fun `male age 25 at boundary 19 is Optimal`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(19f, 25, Gender.MALE))

    @Test
    fun `male age 25 at 20 is Neutral`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(20f, 25, Gender.MALE))

    @Test
    fun `male age 25 at boundary 24 is Neutral`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(24f, 25, Gender.MALE))

    @Test
    fun `male age 25 at 25 is Poor`() =
        assertEquals(BodyFatStatus.Poor, HealthMetricsCalculator.assessBodyFatPercent(25f, 25, Gender.MALE))

    @Test
    fun `male age 50 uses 41-60 thresholds optimal`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(22f, 50, Gender.MALE))

    @Test
    fun `male age 50 neutral range`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(25f, 50, Gender.MALE))

    @Test
    fun `male age 50 above neutralMax is Poor`() =
        assertEquals(BodyFatStatus.Poor, HealthMetricsCalculator.assessBodyFatPercent(29f, 50, Gender.MALE))

    @Test
    fun `male age 70 uses senior thresholds optimal`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(24f, 70, Gender.MALE))

    @Test
    fun `male age 70 neutral range`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(27f, 70, Gender.MALE))

    @Test
    fun `male age 70 above neutralMax is Poor`() =
        assertEquals(BodyFatStatus.Poor, HealthMetricsCalculator.assessBodyFatPercent(31f, 70, Gender.MALE))

    @Test
    fun `female age 25 low fat is Optimal`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(28f, 25, Gender.FEMALE))

    @Test
    fun `female age 25 at boundary 32 is Optimal`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(32f, 25, Gender.FEMALE))

    @Test
    fun `female age 25 at 33 is Neutral`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(33f, 25, Gender.FEMALE))

    @Test
    fun `female age 25 at boundary 38 is Neutral`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(38f, 25, Gender.FEMALE))

    @Test
    fun `female age 25 at 39 is Poor`() =
        assertEquals(BodyFatStatus.Poor, HealthMetricsCalculator.assessBodyFatPercent(39f, 25, Gender.FEMALE))

    @Test
    fun `female age 50 uses 41-60 thresholds`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(34f, 50, Gender.FEMALE))

    @Test
    fun `female age 70 uses senior thresholds`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(36f, 70, Gender.FEMALE))

    @Test
    fun `OTHER gender optimal range`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(20f, 30, Gender.OTHER))

    @Test
    fun `OTHER gender at boundary 25 is Optimal`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(25f, 30, Gender.OTHER))

    @Test
    fun `OTHER gender at 26 is Neutral`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(26f, 30, Gender.OTHER))

    @Test
    fun `OTHER gender at boundary 35 is Neutral`() =
        assertEquals(BodyFatStatus.Neutral, HealthMetricsCalculator.assessBodyFatPercent(35f, 30, Gender.OTHER))

    @Test
    fun `OTHER gender at 36 is Poor`() =
        assertEquals(BodyFatStatus.Poor, HealthMetricsCalculator.assessBodyFatPercent(36f, 30, Gender.OTHER))

    @Test
    fun `PREFER_NOT_TO_SAY gender optimal range`() {
        assertEquals(
            BodyFatStatus.Optimal,
            HealthMetricsCalculator.assessBodyFatPercent(20f, 30, Gender.PREFER_NOT_TO_SAY),
        )
    }

    @Test
    fun `PREFER_NOT_TO_SAY gender Neutral range`() {
        assertEquals(
            BodyFatStatus.Neutral,
            HealthMetricsCalculator.assessBodyFatPercent(30f, 30, Gender.PREFER_NOT_TO_SAY),
        )
    }

    @Test
    fun `PREFER_NOT_TO_SAY gender Poor range`() {
        assertEquals(
            BodyFatStatus.Poor,
            HealthMetricsCalculator.assessBodyFatPercent(36f, 30, Gender.PREFER_NOT_TO_SAY),
        )
    }

    @Test
    fun `age below 20 coerces to 1 which uses senior thresholds for male`() {
        // age 1 not in 20..40 or 41..60, falls to senior thresholds (optimalMax=24, neutralMax=30)
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(24f, 1, Gender.MALE))
    }

    @Test
    fun `age above 120 coerces to 120 which uses senior thresholds for male`() {
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(24f, 200, Gender.MALE))
    }

    @Test
    fun `zero body fat is Optimal for male`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(0f, 25, Gender.MALE))

    @Test
    fun `extremely high body fat is Poor`() =
        assertEquals(BodyFatStatus.Poor, HealthMetricsCalculator.assessBodyFatPercent(60f, 25, Gender.MALE))

    @Test
    fun `male age exactly 40 uses 20-40 thresholds`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(19f, 40, Gender.MALE))

    @Test
    fun `male age exactly 41 uses 41-60 thresholds`() =
        assertEquals(BodyFatStatus.Optimal, HealthMetricsCalculator.assessBodyFatPercent(22f, 41, Gender.MALE))
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
