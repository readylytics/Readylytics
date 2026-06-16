package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RasDepletionHighStrainRuleTest {
    private val rule = RasDepletionHighStrainRule()

    private fun context(
        totalRas: Float? = 30f,
        strainRatio: Float? = 1.2f,
    ) = InsightContext(
        today = dailySummary(legacyTotalRas = totalRas, strainRatio = strainRatio),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
    )

    @Test
    fun `fires when RAS is depleted and strain ratio is high`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.RAS_DEPLETION_HIGH_STRAIN, finding?.type)
        assertEquals(InsightParams.RasDepletionStrain(totalRas = 30f, strainRatio = 1.2f), finding?.params)
    }

    @Test
    fun `does not fire when totalRas is null`() {
        assertNull(rule.evaluate(context(legacyTotalRas = null)))
    }

    @Test
    fun `does not fire when totalRas is at threshold`() {
        assertNull(rule.evaluate(context(legacyTotalRas = 50f)))
    }

    @Test
    fun `does not fire when strain ratio is at threshold`() {
        assertNull(rule.evaluate(context(strainRatio = 1.0f)))
    }

    @Test
    fun `does not fire when strain ratio is null`() {
        assertNull(rule.evaluate(context(strainRatio = null)))
    }

    @Test
    fun `does not fire when totalRas is above threshold even with high strain`() {
        assertNull(rule.evaluate(context(legacyTotalRas = 75f, strainRatio = 2f)))
    }
}
