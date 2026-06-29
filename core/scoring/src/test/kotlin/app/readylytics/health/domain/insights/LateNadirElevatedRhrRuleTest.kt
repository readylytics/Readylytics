package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LateNadirElevatedRhrRuleTest {
    private val rule = LateNadirElevatedRhrRule()

    private fun context(
        lateNadir: Boolean = true,
        rhrDeltaBpm: Float? = 6f,
    ) = InsightContext(
        today = dailySummary(lateNadir = lateNadir, rhrDeltaBpm = rhrDeltaBpm),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
    )

    @Test
    fun `fires when late nadir and elevated RHR both hold`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.LATE_NADIR_ELEVATED_RHR, finding?.type)
        assertEquals(InsightParams.LateNadirElevatedRhr(rhrDeltaBpm = 6f), finding?.params)
    }

    @Test
    fun `does not fire without late nadir`() {
        assertNull(rule.evaluate(context(lateNadir = false)))
    }

    @Test
    fun `does not fire when rhrDeltaBpm is at threshold`() {
        assertNull(rule.evaluate(context(rhrDeltaBpm = 5f)))
    }

    @Test
    fun `does not fire when rhrDeltaBpm is null`() {
        assertNull(rule.evaluate(context(rhrDeltaBpm = null)))
    }

    @Test
    fun `does not fire when rhrDeltaBpm is below threshold`() {
        assertNull(rule.evaluate(context(rhrDeltaBpm = 2f)))
    }
}
