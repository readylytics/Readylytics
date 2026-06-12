package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaiDepletionHighStrainRuleTest {
    private val rule = PaiDepletionHighStrainRule()

    private fun context(
        paiScore: Float? = 30f,
        strainRatio: Float? = 1.2f,
    ) = InsightContext(
        today = dailySummary(paiScore = paiScore, strainRatio = strainRatio),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
    )

    @Test
    fun `fires when PAI is depleted and strain ratio is high`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.PAI_DEPLETION_HIGH_STRAIN, finding?.type)
        assertEquals(InsightParams.PaiDepletionStrain(paiScore = 30f, strainRatio = 1.2f), finding?.params)
    }

    @Test
    fun `does not fire when paiScore is null`() {
        assertNull(rule.evaluate(context(paiScore = null)))
    }

    @Test
    fun `does not fire when paiScore is at threshold`() {
        assertNull(rule.evaluate(context(paiScore = 50f)))
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
    fun `does not fire when paiScore is above threshold even with high strain`() {
        assertNull(rule.evaluate(context(paiScore = 75f, strainRatio = 2f)))
    }
}
