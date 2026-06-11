package app.readylytics.health.domain.scoring.components

import app.readylytics.health.domain.model.PhysiologyConstants
import org.junit.Test
import kotlin.test.assertEquals

class MaxHeartRateCalculatorTest {
    @Test
    fun calculateMaxHeartRate_karvonen_formula() {
        // Age 30 → HRmax = 220 - 30 = 190
        assertEquals(190, MaxHeartRateCalculator.calculateMaxHeartRate(30))

        // Age 40 → HRmax = 220 - 40 = 180
        assertEquals(180, MaxHeartRateCalculator.calculateMaxHeartRate(40))

        // Age 50 → HRmax = 220 - 50 = 170
        assertEquals(170, MaxHeartRateCalculator.calculateMaxHeartRate(50))
    }

    @Test
    fun calculateMaxHeartRate_boundary_ages() {
        // Very young
        assertEquals(202, MaxHeartRateCalculator.calculateMaxHeartRate(18))

        // Middle age
        assertEquals(165, MaxHeartRateCalculator.calculateMaxHeartRate(55))

        // Older adult
        assertEquals(121, MaxHeartRateCalculator.calculateMaxHeartRate(99))
    }

    @Test
    fun calculateMaxHeartRate_never_below_minimum_valid_hrr() {
        // At age 220, formula would give HRmax = 0, floor to MIN_RHR + MIN_VALID_HRR
        val minAllowed = PhysiologyConstants.MINIMUM_RHR_BPM + PhysiologyConstants.MINIMUM_VALID_HRR
        assertEquals(minAllowed, MaxHeartRateCalculator.calculateMaxHeartRate(220))

        // Unrealistic age, still respects floor
        assertEquals(minAllowed, MaxHeartRateCalculator.calculateMaxHeartRate(250))
    }
}
