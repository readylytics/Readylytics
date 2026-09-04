package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.cardio.TsbZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingStressBalanceCalculatorTest {
    private val calculator = TrainingStressBalanceCalculator()

    @Test
    fun returnsNullWhenInputsAreNull() {
        assertNull(calculator.calculate(ctl = null, atl = 50f))
        assertNull(calculator.calculate(ctl = 50f, atl = null))
    }

    @Test
    fun classifiesZonesCorrectly() {
        // TSB = CTL - ATL
        // 60 - 30 = +30 -> VERY_FRESH_OR_TRANSITION
        assertEquals(TsbZone.VERY_FRESH_OR_TRANSITION, calculator.calculate(ctl = 60f, atl = 30f)?.zone)

        // 60 - 45 = +15 -> FRESH_PEAKED
        assertEquals(TsbZone.FRESH_PEAKED, calculator.calculate(ctl = 60f, atl = 45f)?.zone)

        // 60 - 62 = -2 -> OPTIMAL_PRODUCTIVE
        assertEquals(TsbZone.OPTIMAL_PRODUCTIVE, calculator.calculate(ctl = 60f, atl = 62f)?.zone)

        // 60 - 80 = -20 -> FATIGUED_OVERLOAD
        assertEquals(TsbZone.FATIGUED_OVERLOAD, calculator.calculate(ctl = 60f, atl = 80f)?.zone)

        // 60 - 95 = -35 -> HIGH_RISK_OVERREACHED
        assertEquals(TsbZone.HIGH_RISK_OVERREACHED, calculator.calculate(ctl = 60f, atl = 95f)?.zone)
    }
}
