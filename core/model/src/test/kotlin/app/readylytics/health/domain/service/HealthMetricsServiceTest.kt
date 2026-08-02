package app.readylytics.health.domain.service

import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyFatStatus
import app.readylytics.health.domain.model.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DELTA = 0.05

class HealthMetricsServiceTest {
    private lateinit var service: HealthMetricsService

    @Before
    fun setUp() {
        service = HealthMetricsService()
    }

    // ─── calculateBmi ────────────────────────────────────────────────────────
    @Test
    fun calculateBmi_typical_returnsSuccess() {
        val v = service.calculateBmi(70f, 175f)
        assertTrue(v is Result.Success)
        assertEquals(22.86, ((v as Result.Success).data).toDouble(), DELTA)
    }

    @Test
    fun calculateBmi_lowWeight_returnsSuccess() {
        val v = (service.calculateBmi(50f, 180f) as Result.Success).data
        assertEquals(15.43, v.toDouble(), DELTA)
    }

    @Test
    fun calculateBmi_overweight_returnsSuccess() {
        val v = (service.calculateBmi(90f, 170f) as Result.Success).data
        assertEquals(31.14, v.toDouble(), DELTA)
    }

    @Test
    fun calculateBmi_zeroWeight_isFailure() {
        val r = service.calculateBmi(0f, 170f)
        assertTrue(r is Result.Failure)
        assertEquals(HealthMetricsService.Codes.INVALID_WEIGHT, (r as Result.Failure).code)
    }

    @Test
    fun calculateBmi_zeroHeight_isFailure() {
        val r = service.calculateBmi(70f, 0f)
        assertEquals(HealthMetricsService.Codes.INVALID_HEIGHT, (r as Result.Failure).code)
    }

    @Test
    fun calculateBmi_negativeWeight_isFailure() {
        assertTrue(service.calculateBmi(-10f, 170f) is Result.Failure)
    }

    @Test
    fun calculateBmi_negativeHeight_isFailure() {
        assertTrue(service.calculateBmi(70f, -10f) is Result.Failure)
    }

    @Test
    fun calculateBmi_round_100kg_200cm_returns25() {
        val v = (service.calculateBmi(100f, 200f) as Result.Success).data
        assertEquals(25.0, v.toDouble(), DELTA)
    }

    @Test
    fun calculateBmi_isDeterministic() {
        val a = service.calculateBmi(70f, 170f)
        val b = service.calculateBmi(70f, 170f)
        assertEquals(a, b)
    }

    @Test
    fun calculateBmi_failure_reason_isPopulated() {
        val r = service.calculateBmi(0f, 170f) as Result.Failure
        assertTrue(r.reason.isNotBlank())
    }

    // ─── assessBmi ───────────────────────────────────────────────────────────
    @Test
    fun assessBmi_boundary18_49_isWarning() = assertEquals(BmiStatus.Warning, service.assessBmi(18.49f))

    @Test
    fun assessBmi_boundary18_5_isOptimal() = assertEquals(BmiStatus.Optimal, service.assessBmi(18.5f))

    @Test
    fun assessBmi_below25_isOptimal() = assertEquals(BmiStatus.Optimal, service.assessBmi(24.9f))

    @Test
    fun assessBmi_at25_isWarning() = assertEquals(BmiStatus.Warning, service.assessBmi(25f))

    @Test
    fun assessBmi_27_isWarning() = assertEquals(BmiStatus.Warning, service.assessBmi(27f))

    @Test
    fun assessBmi_at30_isPoor() = assertEquals(BmiStatus.Poor, service.assessBmi(30f))

    @Test
    fun assessBmi_32_isPoor() = assertEquals(BmiStatus.Poor, service.assessBmi(32f))

    @Test
    fun assessBmi_at35_isPoor() = assertEquals(BmiStatus.Poor, service.assessBmi(35f))

    @Test
    fun assessBmi_zero_isWarning() = assertEquals(BmiStatus.Warning, service.assessBmi(0f))

    @Test
    fun assessBmi_negative_isWarning() = assertEquals(BmiStatus.Warning, service.assessBmi(-5f))

    @Test
    fun assessBmi_boundary29_9_isWarning() = assertEquals(BmiStatus.Warning, service.assessBmi(29.9f))

    @Test
    fun assessBmi_boundary34_9_isPoor() = assertEquals(BmiStatus.Poor, service.assessBmi(34.9f))

    // ─── assessBloodPressure ─────────────────────────────────────────────────
    @Test
    fun bp_110_70_isOptimal() = assertEquals(BloodPressureStatus.Optimal, service.assessBloodPressure(110, 70))

    @Test
    fun bp_119_79_isOptimal() = assertEquals(BloodPressureStatus.Optimal, service.assessBloodPressure(119, 79))

    @Test
    fun bp_120_80_isOptimal() = assertEquals(BloodPressureStatus.Optimal, service.assessBloodPressure(120, 80))

    @Test
    fun bp_121_80_isNeutral() = assertEquals(BloodPressureStatus.Neutral, service.assessBloodPressure(121, 80))

    @Test
    fun bp_120_81_isNeutral() = assertEquals(BloodPressureStatus.Neutral, service.assessBloodPressure(120, 81))

    @Test
    fun bp_129_89_isNeutral() = assertEquals(BloodPressureStatus.Neutral, service.assessBloodPressure(129, 89))

    @Test
    fun bp_130_90_isStage1() =
        assertEquals(BloodPressureStatus.HypertensionStage1, service.assessBloodPressure(130, 90))

    @Test
    fun bp_130_75_isStage1() =
        assertEquals(BloodPressureStatus.HypertensionStage1, service.assessBloodPressure(130, 75))

    @Test
    fun bp_139_85_isStage1() =
        assertEquals(BloodPressureStatus.HypertensionStage1, service.assessBloodPressure(139, 85))

    @Test
    fun bp_115_80_isOptimal() =
        assertEquals(BloodPressureStatus.Optimal, service.assessBloodPressure(115, 80))

    @Test
    fun bp_115_89_isNeutral() =
        assertEquals(BloodPressureStatus.Neutral, service.assessBloodPressure(115, 89))

    @Test
    fun bp_140_70_isStage2() =
        assertEquals(BloodPressureStatus.HypertensionStage2, service.assessBloodPressure(140, 70))

    @Test
    fun bp_115_90_isStage1() =
        assertEquals(BloodPressureStatus.HypertensionStage1, service.assessBloodPressure(115, 90))

    @Test
    fun bp_180_110_isStage2() =
        assertEquals(BloodPressureStatus.HypertensionStage2, service.assessBloodPressure(180, 110))

    // ─── assessBodyFatPercent ────────────────────────────────────────────────
    // Canonical, continuous bands via BodyCompositionAssessment; independent of profile for
    // status (profile only shifts the gauge reference midpoint, covered by
    // BodyCompositionAssessmentTest).
    @Test
    fun bf_nullGender_usesFixedReferenceBand() =
        assertEquals(
            BodyFatStatus.Optimal,
            service.assessBodyFatPercent(20f, PhysiologyProfile.ACTIVE, null),
        )

    @Test
    fun bf_male_belowEssential_isWarning() =
        assertEquals(
            BodyFatStatus.Warning,
            service.assessBodyFatPercent(1.99f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun bf_male_boundary2_isNeutral() =
        assertEquals(
            BodyFatStatus.Neutral,
            service.assessBodyFatPercent(2f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun bf_male_athletic_isOptimal() =
        assertEquals(
            BodyFatStatus.Optimal,
            service.assessBodyFatPercent(6f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun bf_male_acceptable_isNeutral() =
        assertEquals(
            BodyFatStatus.Neutral,
            service.assessBodyFatPercent(18f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun bf_male_obese_isPoor() =
        assertEquals(
            BodyFatStatus.Poor,
            service.assessBodyFatPercent(25f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun bf_female_belowEssential_isWarning() =
        assertEquals(
            BodyFatStatus.Warning,
            service.assessBodyFatPercent(9.99f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun bf_female_boundary10_isNeutral() =
        assertEquals(
            BodyFatStatus.Neutral,
            service.assessBodyFatPercent(10f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun bf_female_athletic_isOptimal() =
        assertEquals(
            BodyFatStatus.Optimal,
            service.assessBodyFatPercent(14f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun bf_female_acceptable_isNeutral() =
        assertEquals(
            BodyFatStatus.Neutral,
            service.assessBodyFatPercent(25f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun bf_female_obese_isPoor() =
        assertEquals(
            BodyFatStatus.Poor,
            service.assessBodyFatPercent(32f, PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )

    @Test
    fun bf_other_withinFixedBand_isOptimal() =
        assertEquals(
            BodyFatStatus.Optimal,
            service.assessBodyFatPercent(20f, PhysiologyProfile.SEDENTARY, Gender.OTHER),
        )

    @Test
    fun bf_other_atFixedMinimum_isNeutral() =
        assertEquals(
            BodyFatStatus.Neutral,
            service.assessBodyFatPercent(10f, PhysiologyProfile.SEDENTARY, Gender.OTHER),
        )

    @Test
    fun bf_other_aboveFixedMaximum_isPoor() =
        assertEquals(
            BodyFatStatus.Poor,
            service.assessBodyFatPercent(30.01f, PhysiologyProfile.SEDENTARY, Gender.OTHER),
        )

    @Test
    fun bf_preferNotToSay_withinFixedBand_isOptimal() =
        assertEquals(
            BodyFatStatus.Optimal,
            service.assessBodyFatPercent(20f, PhysiologyProfile.ATHLETE, Gender.PREFER_NOT_TO_SAY),
        )

    @Test
    fun bf_zero_isBelowEssentialWarning() =
        assertEquals(
            BodyFatStatus.Warning,
            service.assessBodyFatPercent(0f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun bf_extreme_isPoor() =
        assertEquals(
            BodyFatStatus.Poor,
            service.assessBodyFatPercent(60f, PhysiologyProfile.ACTIVE, Gender.MALE),
        )

    @Test
    fun bf_male_statusIndependentOfProfile() {
        assertEquals(
            service.assessBodyFatPercent(19f, PhysiologyProfile.ATHLETE, Gender.MALE),
            service.assessBodyFatPercent(19f, PhysiologyProfile.SEDENTARY, Gender.MALE),
        )
    }

    // ─── calculateDailyBpAverage ─────────────────────────────────────────────
    @Test
    fun bpAvg_empty_isFailure() {
        assertTrue(service.calculateDailyBpAverage(emptyList(), emptyList()) is Result.Failure)
    }

    @Test
    fun bpAvg_emptySystolics_isFailure() {
        assertTrue(service.calculateDailyBpAverage(emptyList(), listOf(80)) is Result.Failure)
    }

    @Test
    fun bpAvg_emptyDiastolics_isFailure() {
        assertTrue(service.calculateDailyBpAverage(listOf(120), emptyList()) is Result.Failure)
    }

    @Test
    fun bpAvg_empty_code_isEmptySeries() {
        val r = service.calculateDailyBpAverage(emptyList(), emptyList()) as Result.Failure
        assertEquals(HealthMetricsService.Codes.EMPTY_SERIES, r.code)
    }

    @Test
    fun bpAvg_single_returnsSame() {
        val v = (service.calculateDailyBpAverage(listOf(120), listOf(80)) as Result.Success).data
        assertEquals(Pair(120, 80), v)
    }

    @Test
    fun bpAvg_two_averagesCorrectly() {
        val v = (service.calculateDailyBpAverage(listOf(110, 120), listOf(70, 80)) as Result.Success).data
        assertEquals(Pair(115, 75), v)
    }

    @Test
    fun bpAvg_three_averagesCorrectly() {
        val v = (service.calculateDailyBpAverage(listOf(110, 120, 130), listOf(70, 75, 80)) as Result.Success).data
        assertEquals(Pair(120, 75), v)
    }

    @Test
    fun bpAvg_truncatesToInt() {
        val v = (service.calculateDailyBpAverage(listOf(110, 121), listOf(70, 71)) as Result.Success).data
        assertEquals(115, v.first)
        assertEquals(70, v.second)
    }

    // ─── mean / median ───────────────────────────────────────────────────────
    @Test
    fun mean_empty_isFailure() = assertTrue(service.mean(emptyList()) is Result.Failure)

    @Test
    fun mean_single_returnsValue() {
        assertEquals(42.0, (service.mean(listOf(42)) as Result.Success).data, 0.001)
    }

    @Test
    fun mean_multiple_average() {
        assertEquals(2.0, (service.mean(listOf(1, 2, 3)) as Result.Success).data, 0.001)
    }

    @Test
    fun median_empty_isFailure() = assertTrue(service.median(emptyList()) is Result.Failure)

    @Test
    fun median_odd_returnsMiddle() {
        assertEquals(3.0, (service.median(listOf(1, 3, 5)) as Result.Success).data, 0.001)
    }

    @Test
    fun median_even_returnsAverageOfTwoMiddle() {
        assertEquals(3.0, (service.median(listOf(1, 2, 4, 6)) as Result.Success).data, 0.001)
    }

    @Test
    fun median_unsorted_sortsBeforeComputing() {
        assertEquals(5.0, (service.median(listOf(9, 1, 5, 3, 7)) as Result.Success).data, 0.001)
    }

    @Test
    fun median_single_returnsValue() {
        assertEquals(7.0, (service.median(listOf(7)) as Result.Success).data, 0.001)
    }

    // ─── constants ───────────────────────────────────────────────────────────
    @Test
    fun constants_thresholds_areCorrect() {
        assertEquals(120, HealthMetricsService.BP_NORMAL_SYS)
        assertEquals(80, HealthMetricsService.BP_NORMAL_DIA)
    }
}
