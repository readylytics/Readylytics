package app.readylytics.health.domain.scoring.components

import app.readylytics.health.domain.model.PhysiologyConstants
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaxHeartRateCalculatorTest {
    @Test
    fun `calculateMaxHeartRate returns 190 for 30-year-old`() {
        val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(30)
        assertEquals(190, maxHr)
    }

    @Test
    fun `calculateMaxHeartRate returns 200 for 20-year-old`() {
        val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(20)
        assertEquals(200, maxHr)
    }

    @Test
    fun `calculateMaxHeartRate returns 170 for 50-year-old`() {
        val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(50)
        assertEquals(170, maxHr)
    }

    @Test
    fun `calculateMaxHeartRate returns 220 for newborn`() {
        val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(0)
        assertEquals(220, maxHr)
    }

    @Test
    fun `calculateMaxHeartRate applies floor at boundary age`() {
        val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(186)
        val minFloor = PhysiologyConstants.MINIMUM_RHR_BPM + PhysiologyConstants.MINIMUM_VALID_HRR
        assertEquals(minFloor, maxHr)
    }

    @Test
    fun `calculateMaxHeartRate result is always above minimum`() {
        for (age in 0..100) {
            val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(age)
            val minFloor = PhysiologyConstants.MINIMUM_RHR_BPM + PhysiologyConstants.MINIMUM_VALID_HRR
            assertTrue(maxHr >= minFloor, "Max HR for age $age should be >= $minFloor")
        }
    }

    @Test
    fun `calculateMaxHeartRate decreases with age`() {
        val hr20 = MaxHeartRateCalculator.calculateMaxHeartRate(20)
        val hr30 = MaxHeartRateCalculator.calculateMaxHeartRate(30)
        val hr40 = MaxHeartRateCalculator.calculateMaxHeartRate(40)
        assertTrue(hr20 > hr30 && hr30 > hr40)
    }

    @Test
    fun `calculateMaxHeartRate follows Karvonen formula`() {
        for (age in 18..65) {
            val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(age)
            assertEquals(220 - age, maxHr)
        }
    }

    @Test
    fun `calculateMaxHeartRate handles negative age gracefully`() {
        val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(-5)
        assertTrue(maxHr > 0)
    }

    @Test
    fun `calculateMaxHeartRate handles very high age`() {
        val maxHr = MaxHeartRateCalculator.calculateMaxHeartRate(200)
        val minFloor = PhysiologyConstants.MINIMUM_RHR_BPM + PhysiologyConstants.MINIMUM_VALID_HRR
        assertEquals(minFloor, maxHr)
    }
}
