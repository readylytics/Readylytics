package app.readylytics.health.domain.service

import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.UnitSystem
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BmiServiceTest {
    private lateinit var service: BmiService

    @Before
    fun setup() {
        service = BmiService()
    }

    @Test
    fun `calculates BMI correctly for metric inputs`() {
        val result = service.calculateBmi(weight = 70f, height = 175f, units = UnitSystem.METRIC)
        assertTrue(result is Result.Success)
        val bmi = (result as Result.Success).data.bmi
        assertEquals(22.86f, bmi, 0.1f)
    }

    @Test
    fun `converts imperial to metric correctly`() {
        val result = service.calculateBmi(weight = 154f, height = 69f, units = UnitSystem.IMPERIAL)
        assertTrue(result is Result.Success)
    }

    @Test
    fun `classifies BMI as Optimal when under 25`() {
        val result = service.calculateBmi(weight = 70f, height = 175f, units = UnitSystem.METRIC)
        val status = (result as Result.Success).data.status
        assertEquals(BmiStatus.Optimal, status)
    }

    @Test
    fun `classifies BMI as Neutral when 25-30`() {
        val result = service.calculateBmi(weight = 82f, height = 175f, units = UnitSystem.METRIC)
        val status = (result as Result.Success).data.status
        assertEquals(BmiStatus.Neutral, status)
    }

    @Test
    fun `classifies BMI as Warning when 30-35`() {
        val result = service.calculateBmi(weight = 92f, height = 175f, units = UnitSystem.METRIC)
        val status = (result as Result.Success).data.status
        assertEquals(BmiStatus.Warning, status)
    }

    @Test
    fun `classifies BMI as Poor when 35 or above`() {
        val result = service.calculateBmi(weight = 108f, height = 175f, units = UnitSystem.METRIC)
        val status = (result as Result.Success).data.status
        assertEquals(BmiStatus.Poor, status)
    }

    @Test
    fun `rejects negative weight`() {
        val result = service.calculateBmi(weight = -70f, height = 175f, units = UnitSystem.METRIC)
        assertTrue(result is Result.Failure)
        assertEquals(BmiService.Codes.WEIGHT_NOT_POSITIVE, (result as Result.Failure).code)
    }

    @Test
    fun `rejects zero weight`() {
        val result = service.calculateBmi(weight = 0f, height = 175f, units = UnitSystem.METRIC)
        assertTrue(result is Result.Failure)
    }

    @Test
    fun `rejects negative height`() {
        val result = service.calculateBmi(weight = 70f, height = -175f, units = UnitSystem.METRIC)
        assertTrue(result is Result.Failure)
        assertEquals(BmiService.Codes.HEIGHT_NOT_POSITIVE, (result as Result.Failure).code)
    }

    @Test
    fun `rejects zero height`() {
        val result = service.calculateBmi(weight = 70f, height = 0f, units = UnitSystem.METRIC)
        assertTrue(result is Result.Failure)
    }

    @Test
    fun `rejects weight exceeding maximum`() {
        val result = service.calculateBmi(weight = 1001f, height = 175f, units = UnitSystem.METRIC)
        assertTrue(result is Result.Failure)
        assertEquals(BmiService.Codes.WEIGHT_TOO_HIGH, (result as Result.Failure).code)
    }

    @Test
    fun `rejects height exceeding maximum`() {
        val result = service.calculateBmi(weight = 70f, height = 301f, units = UnitSystem.METRIC)
        assertTrue(result is Result.Failure)
        assertEquals(BmiService.Codes.HEIGHT_TOO_HIGH, (result as Result.Failure).code)
    }

    @Test
    fun `classify function works standalone`() {
        val optimal = service.classify(24f)
        val neutral = service.classify(27f)
        val warning = service.classify(32f)
        val poor = service.classify(36f)

        assertEquals(BmiStatus.Optimal, optimal)
        assertEquals(BmiStatus.Neutral, neutral)
        assertEquals(BmiStatus.Warning, warning)
        assertEquals(BmiStatus.Poor, poor)
    }

    @Test
    fun `BMI calculation matches formula weight_kg over height_m_squared`() {
        val weight = 75f
        val height = 180f
        val result = service.calculateBmi(weight, height, UnitSystem.METRIC)
        val calculatedBmi = (result as Result.Success).data.bmi
        val expectedBmi = weight / ((height / 100f) * (height / 100f))
        assertEquals(expectedBmi, calculatedBmi, 0.01f)
    }

    @Test
    fun `imperial conversion includes both weight and height`() {
        val weightLbs = 150f
        val heightInches = 70f
        val result = service.calculateBmi(weightLbs, heightInches, UnitSystem.IMPERIAL)
        assertTrue(result is Result.Success)
        val bmi = (result as Result.Success).data.bmi
        assertTrue(bmi in 20f..22f)
    }

    @Test
    fun `boundary BMI 25 classifies as Neutral not Optimal`() {
        val result = service.calculateBmi(weight = 81.25f, height = 180f, units = UnitSystem.METRIC)
        val status = (result as Result.Success).data.status
        assertEquals(BmiStatus.Neutral, status)
    }

    @Test
    fun `boundary BMI 30 classifies as Warning not Neutral`() {
        val result = service.calculateBmi(weight = 97.2f, height = 180f, units = UnitSystem.METRIC)
        val status = (result as Result.Success).data.status
        assertEquals(BmiStatus.Warning, status)
    }
}
