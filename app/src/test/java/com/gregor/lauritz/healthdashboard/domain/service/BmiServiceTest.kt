package com.gregor.lauritz.healthdashboard.domain.service

import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.domain.model.BmiStatus
import com.gregor.lauritz.healthdashboard.domain.model.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val DELTA = 0.05f

class BmiServiceTest {
    private lateinit var service: BmiService

    @Before
    fun setUp() {
        service = BmiService()
    }

    // ─── calculateBmi: METRIC happy path ─────────────────────────────────────
    @Test
    fun calculateBmi_metric_typical_returnsSuccess() {
        val r = service.calculateBmi(70f, 175f, UnitSystem.METRIC)
        assertTrue(r is Result.Success)
        val s = (r as Result.Success).data
        assertEquals(22.86f, s.bmi, DELTA)
        assertEquals(BmiStatus.Optimal, s.status)
    }

    @Test
    fun calculateBmi_metric_overweight_classifiesWarning() {
        val s = (service.calculateBmi(90f, 170f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(BmiStatus.Warning, s.status)
    }

    @Test
    fun calculateBmi_metric_lowWeight_classifiesOptimal() {
        val s = (service.calculateBmi(50f, 180f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(BmiStatus.Optimal, s.status)
    }

    @Test
    fun calculateBmi_metric_roundNumbers_25exact_isNeutral() {
        val s = (service.calculateBmi(100f, 200f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(25.0f, s.bmi, DELTA)
        assertEquals(BmiStatus.Neutral, s.status)
    }

    @Test
    fun calculateBmi_metric_small_returnsOptimal() {
        val s = (service.calculateBmi(30f, 150f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(13.33f, s.bmi, DELTA)
        assertEquals(BmiStatus.Optimal, s.status)
    }

    @Test
    fun calculateBmi_metric_obeseClass1_isWarning() {
        val s = (service.calculateBmi(95f, 170f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(BmiStatus.Warning, s.status)
    }

    @Test
    fun calculateBmi_metric_obeseClass2_isPoor() {
        val s = (service.calculateBmi(110f, 170f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(BmiStatus.Poor, s.status)
    }

    @Test
    fun calculateBmi_metric_height160_weight80() {
        val s = (service.calculateBmi(80f, 160f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(31.25f, s.bmi, DELTA)
    }

    @Test
    fun calculateBmi_metric_height180_weight65() {
        val s = (service.calculateBmi(65f, 180f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(20.06f, s.bmi, DELTA)
    }

    @Test
    fun calculateBmi_metric_height200_weight75() {
        val s = (service.calculateBmi(75f, 200f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(18.75f, s.bmi, DELTA)
    }

    // ─── calculateBmi: IMPERIAL happy path ───────────────────────────────────
    @Test
    fun calculateBmi_imperial_typical_returnsSuccess() {
        val s = (service.calculateBmi(154.32f, 68.9f, UnitSystem.IMPERIAL) as Result.Success).data
        assertEquals(22.86f, s.bmi, 0.2f)
    }

    @Test
    fun calculateBmi_imperial_lightWeight_isOptimal() {
        val s = (service.calculateBmi(110f, 70f, UnitSystem.IMPERIAL) as Result.Success).data
        assertEquals(BmiStatus.Optimal, s.status)
    }

    @Test
    fun calculateBmi_imperial_overweight_isNeutralOrWarning() {
        val s = (service.calculateBmi(220f, 68f, UnitSystem.IMPERIAL) as Result.Success).data
        assertTrue(s.status is BmiStatus.Warning || s.status is BmiStatus.Neutral)
    }

    @Test
    fun calculateBmi_imperial_obese_isPoorOrWarning() {
        val s = (service.calculateBmi(280f, 66f, UnitSystem.IMPERIAL) as Result.Success).data
        assertTrue(s.status is BmiStatus.Poor || s.status is BmiStatus.Warning)
    }

    @Test
    fun calculateBmi_imperial_height72_weight180() {
        val s = (service.calculateBmi(180f, 72f, UnitSystem.IMPERIAL) as Result.Success).data
        assertNotNull(s)
        assertTrue(s.bmi in 22f..26f)
    }

    // ─── calculateBmi: failure paths ─────────────────────────────────────────
    @Test
    fun calculateBmi_zeroWeight_returnsFailureWeightNotPositive() {
        val r = service.calculateBmi(0f, 170f, UnitSystem.METRIC) as Result.Failure
        assertEquals(BmiService.Codes.WEIGHT_NOT_POSITIVE, r.code)
    }

    @Test
    fun calculateBmi_negativeWeight_returnsFailure() {
        val r = service.calculateBmi(-1f, 170f, UnitSystem.METRIC) as Result.Failure
        assertEquals(BmiService.Codes.WEIGHT_NOT_POSITIVE, r.code)
    }

    @Test
    fun calculateBmi_zeroHeight_returnsFailureHeightNotPositive() {
        val r = service.calculateBmi(70f, 0f, UnitSystem.METRIC) as Result.Failure
        assertEquals(BmiService.Codes.HEIGHT_NOT_POSITIVE, r.code)
    }

    @Test
    fun calculateBmi_negativeHeight_returnsFailure() {
        val r = service.calculateBmi(70f, -1f, UnitSystem.METRIC) as Result.Failure
        assertEquals(BmiService.Codes.HEIGHT_NOT_POSITIVE, r.code)
    }

    @Test
    fun calculateBmi_excessiveWeight_returnsFailureWeightTooHigh() {
        val r = service.calculateBmi(2_000f, 170f, UnitSystem.METRIC) as Result.Failure
        assertEquals(BmiService.Codes.WEIGHT_TOO_HIGH, r.code)
    }

    @Test
    fun calculateBmi_excessiveHeight_returnsFailureHeightTooHigh() {
        val r = service.calculateBmi(70f, 500f, UnitSystem.METRIC) as Result.Failure
        assertEquals(BmiService.Codes.HEIGHT_TOO_HIGH, r.code)
    }

    @Test
    fun calculateBmi_bothZero_failsWeightFirst() {
        val r = service.calculateBmi(0f, 0f, UnitSystem.METRIC) as Result.Failure
        assertEquals(BmiService.Codes.WEIGHT_NOT_POSITIVE, r.code)
    }

    @Test
    fun calculateBmi_imperialZeroWeight_isFailure() {
        val r = service.calculateBmi(0f, 70f, UnitSystem.IMPERIAL) as Result.Failure
        assertEquals(BmiService.Codes.WEIGHT_NOT_POSITIVE, r.code)
    }

    @Test
    fun calculateBmi_imperialZeroHeight_isFailure() {
        val r = service.calculateBmi(150f, 0f, UnitSystem.IMPERIAL) as Result.Failure
        assertEquals(BmiService.Codes.HEIGHT_NOT_POSITIVE, r.code)
    }

    @Test
    fun calculateBmi_failure_reason_isHumanReadable() {
        val r = service.calculateBmi(0f, 170f, UnitSystem.METRIC) as Result.Failure
        assertTrue(r.reason.isNotBlank())
    }

    // ─── classify ────────────────────────────────────────────────────────────
    @Test
    fun classify_bmi_below25_isOptimal() = assertEquals(BmiStatus.Optimal, service.classify(24.9f))

    @Test
    fun classify_bmi_at25_isNeutral() = assertEquals(BmiStatus.Neutral, service.classify(25.0f))

    @Test
    fun classify_bmi_27_isNeutral() = assertEquals(BmiStatus.Neutral, service.classify(27f))

    @Test
    fun classify_bmi_29_9_isNeutral() = assertEquals(BmiStatus.Neutral, service.classify(29.9f))

    @Test
    fun classify_bmi_at30_isWarning() = assertEquals(BmiStatus.Warning, service.classify(30.0f))

    @Test
    fun classify_bmi_32_isWarning() = assertEquals(BmiStatus.Warning, service.classify(32f))

    @Test
    fun classify_bmi_34_9_isWarning() = assertEquals(BmiStatus.Warning, service.classify(34.9f))

    @Test
    fun classify_bmi_at35_isPoor() = assertEquals(BmiStatus.Poor, service.classify(35.0f))

    @Test
    fun classify_bmi_40_isPoor() = assertEquals(BmiStatus.Poor, service.classify(40f))

    @Test
    fun classify_bmi_zero_isOptimal() = assertEquals(BmiStatus.Optimal, service.classify(0f))

    @Test
    fun classify_bmi_negative_isOptimal() = assertEquals(BmiStatus.Optimal, service.classify(-5f))

    @Test
    fun classify_bmi_10_isOptimal() = assertEquals(BmiStatus.Optimal, service.classify(10f))

    @Test
    fun classify_bmi_18point5_isOptimal() = assertEquals(BmiStatus.Optimal, service.classify(18.5f))

    @Test
    fun classify_bmi_22_isOptimal() = assertEquals(BmiStatus.Optimal, service.classify(22f))

    @Test
    fun classify_bmi_24point99_isOptimal() = assertEquals(BmiStatus.Optimal, service.classify(24.99f))

    @Test
    fun classify_bmi_25point01_isNeutral() = assertEquals(BmiStatus.Neutral, service.classify(25.01f))

    @Test
    fun classify_bmi_29point99_isNeutral() = assertEquals(BmiStatus.Neutral, service.classify(29.99f))

    @Test
    fun classify_bmi_30point01_isWarning() = assertEquals(BmiStatus.Warning, service.classify(30.01f))

    @Test
    fun classify_bmi_34point99_isWarning() = assertEquals(BmiStatus.Warning, service.classify(34.99f))

    @Test
    fun classify_bmi_35point01_isPoor() = assertEquals(BmiStatus.Poor, service.classify(35.01f))

    @Test
    fun classify_bmi_50_isPoor() = assertEquals(BmiStatus.Poor, service.classify(50f))

    @Test
    fun classify_bmi_100_isPoor() = assertEquals(BmiStatus.Poor, service.classify(100f))

    // ─── Result and Failure semantics ────────────────────────────────────────
    @Test
    fun failure_isFailure_isTrue() {
        val r = service.calculateBmi(0f, 100f, UnitSystem.METRIC)
        assertTrue(r is Result.Failure)
        assertTrue(r.isFailure)
    }

    @Test
    fun success_data_carriesBmiAndStatus() {
        val s = (service.calculateBmi(70f, 170f, UnitSystem.METRIC) as Result.Success).data
        assertNotNull(s.bmi)
        assertNotNull(s.status)
    }

    @Test
    fun constants_overweightThreshold_is25() {
        assertEquals(25f, BmiService.OVERWEIGHT_THRESHOLD, 0f)
    }

    @Test
    fun constants_obeseClass1Threshold_is30() {
        assertEquals(30f, BmiService.OBESE_CLASS_1_THRESHOLD, 0f)
    }

    @Test
    fun constants_obeseClass2Threshold_is35() {
        assertEquals(35f, BmiService.OBESE_CLASS_2_THRESHOLD, 0f)
    }

    @Test
    fun calculateBmi_metricAndImperial_areConsistent() {
        val metric = (service.calculateBmi(70f, 175f, UnitSystem.METRIC) as Result.Success).data
        val imperial =
            (service.calculateBmi(70f * 2.20462f, 175f * 0.393701f, UnitSystem.IMPERIAL) as Result.Success).data
        assertEquals(metric.bmi, imperial.bmi, 0.1f)
    }

    @Test
    fun calculateBmi_metric_extremelyLow_isOptimal() {
        val s = (service.calculateBmi(35f, 180f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(BmiStatus.Optimal, s.status)
    }

    @Test
    fun calculateBmi_metric_borderlineOverweight() {
        val s = (service.calculateBmi(76.6f, 175f, UnitSystem.METRIC) as Result.Success).data
        assertTrue(s.status is BmiStatus.Neutral || s.status is BmiStatus.Optimal)
    }

    @Test
    fun calculateBmi_metric_height100kg_weight73point5() {
        val s = (service.calculateBmi(73.5f, 100f, UnitSystem.METRIC) as Result.Success).data
        assertEquals(73.5f, s.bmi, 0.01f)
    }

    @Test
    fun calculateBmi_metric_smallestValidWeight_succeeds() {
        val r = service.calculateBmi(0.01f, 170f, UnitSystem.METRIC)
        assertTrue(r is Result.Success)
    }

    @Test
    fun calculateBmi_metric_smallestValidHeight_succeeds() {
        val r = service.calculateBmi(70f, 0.01f, UnitSystem.METRIC)
        assertTrue(r is Result.Success)
    }

    @Test
    fun calculateBmi_excessiveWeightImperial_returnsFailure() {
        val r = service.calculateBmi(2_500f, 70f, UnitSystem.IMPERIAL) as Result.Failure
        assertEquals(BmiService.Codes.WEIGHT_TOO_HIGH, r.code)
    }

    @Test
    fun calculateBmi_excessiveHeightImperial_returnsFailure() {
        val r = service.calculateBmi(150f, 400f, UnitSystem.IMPERIAL) as Result.Failure
        assertEquals(BmiService.Codes.HEIGHT_TOO_HIGH, r.code)
    }

    @Test
    fun classify_bmi_isPureFunction() {
        val a = service.classify(27f)
        val b = service.classify(27f)
        assertEquals(a, b)
    }

    @Test
    fun calculateBmi_isDeterministic() {
        val r1 = service.calculateBmi(70f, 175f, UnitSystem.METRIC)
        val r2 = service.calculateBmi(70f, 175f, UnitSystem.METRIC)
        assertEquals(r1, r2)
    }

    @Test
    fun calculateBmi_resultIsAlwaysSealedSubtype() {
        val r1 = service.calculateBmi(70f, 175f, UnitSystem.METRIC)
        val r2 = service.calculateBmi(0f, 175f, UnitSystem.METRIC)
        assertTrue(r1 is Result.Success || r1 is Result.Failure)
        assertTrue(r2 is Result.Success || r2 is Result.Failure)
    }
}
