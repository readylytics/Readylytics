package app.readylytics.health.core.scoring.domain.cardio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UthVo2MaxCalculatorTest {
    private val calculator = UthVo2MaxCalculator()

    @Test
    fun returnsNullWhenCalibrating() {
        val result = calculator.estimate(hrMax = 190f, rhrBaselineBpm = 60f, isCalibrating = true)
        assertNull(result)
    }

    @Test
    fun computesExpectedEstimateFromHeartRateRatio() {
        // 15.3 * (190 / 60) = 48.45
        val result = calculator.estimate(hrMax = 190f, rhrBaselineBpm = 60f, isCalibrating = false)
        assertEquals(48.45f, result!!, 0.05f)
    }

    @Test
    fun clampsToPhysiologicalBounds() {
        val extremeHigh = calculator.estimate(hrMax = 220f, rhrBaselineBpm = 30f, isCalibrating = false)
        assertEquals(95.0f, extremeHigh!!, 0.01f)

        val extremeLow = calculator.estimate(hrMax = 100f, rhrBaselineBpm = 110f, isCalibrating = false)
        assertEquals(15.0f, extremeLow!!, 0.01f)
    }
}
