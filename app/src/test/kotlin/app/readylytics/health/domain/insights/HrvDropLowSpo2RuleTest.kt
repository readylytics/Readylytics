package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrvDropLowSpo2RuleTest {
    private val rule = HrvDropLowSpo2Rule()

    private fun context(
        zLnHrv: Float? = -1.6f,
        avgSleepingSpo2: Float? = 93f,
    ) = InsightContext(
        today = dailySummary(zLnHrv = zLnHrv, avgSleepingSpo2 = avgSleepingSpo2),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
    )

    @Test
    fun `fires when HRV drop and low SpO2 both hold`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.HRV_DROP_LOW_SPO2, finding?.type)
        assertEquals(InsightParams.HrvSpo2(zLnHrv = -1.6f, spo2 = 93f), finding?.params)
    }

    @Test
    fun `does not fire when zLnHrv is null`() {
        assertNull(rule.evaluate(context(zLnHrv = null)))
    }

    @Test
    fun `does not fire when avgSleepingSpo2 is null`() {
        assertNull(rule.evaluate(context(avgSleepingSpo2 = null)))
    }

    @Test
    fun `does not fire when zLnHrv is at threshold`() {
        assertNull(rule.evaluate(context(zLnHrv = -1.5f)))
    }

    @Test
    fun `does not fire when spo2 is at threshold`() {
        assertNull(rule.evaluate(context(avgSleepingSpo2 = 94f)))
    }

    @Test
    fun `does not fire when only HRV drops but SpO2 is normal`() {
        assertNull(rule.evaluate(context(avgSleepingSpo2 = 97f)))
    }

    @Test
    fun `does not fire when only SpO2 is low but HRV is normal`() {
        assertNull(rule.evaluate(context(zLnHrv = 0.2f)))
    }
}
