package app.readylytics.health.core.scoring.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** SCORE-104 / OD-3: Tanaka hrMax is truncated (rounded down) by design. Do not change to roundToInt(). */
class HeartRateFormulasTest {
    @Test
    fun `fractional Tanaka result is rounded down`() {
        // 208 - 0.7 * 35 = 183.5
        assertEquals(183, HeartRateFormulas.estimateMaxHr(35))
    }

    @Test
    fun `integral Tanaka result is unchanged`() {
        // 208 - 0.7 * 30 = 187.0
        assertEquals(187, HeartRateFormulas.estimateMaxHr(30))
    }
}
