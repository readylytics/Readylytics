package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighStrainSleepDeficitRuleTest {
    private val rule = HighStrainSleepDeficitRule()

    private fun context(
        zLnHrv: Float? = -1.2f,
        strainRatio: Float? = 1.5f,
        sleepDurationMinutes: Int? = 360,
        goalSleepMinutes: Int = 480,
    ) = InsightContext(
        today =
            dailySummary(
                strainRatio = strainRatio,
                sleepDurationMinutes = sleepDurationMinutes,
                zLnHrv = zLnHrv,
            ),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = goalSleepMinutes,
    )

    @Test
    fun `fires when high strain ratio, sleep deficit and recovery strain marker all hold`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.HIGH_STRAIN_SLEEP_DEFICIT, finding?.type)
        assertEquals(
            InsightParams.HighStrainSleepDeficit(strainRatio = 1.5f, sleepDeficitMinutes = 120),
            finding?.params,
        )
    }

    @Test
    fun `does not fire without recovery strain marker`() {
        assertNull(rule.evaluate(context(zLnHrv = 0.0f)))
    }

    @Test
    fun `does not fire when strain ratio is at threshold`() {
        assertNull(rule.evaluate(context(strainRatio = 1.3f)))
    }

    @Test
    fun `does not fire when strain ratio is null`() {
        assertNull(rule.evaluate(context(strainRatio = null)))
    }

    @Test
    fun `does not fire when sleep duration is at deficit ratio boundary`() {
        // 480 * 0.85 = 408
        assertNull(rule.evaluate(context(sleepDurationMinutes = 408)))
    }

    @Test
    fun `does not fire when sleep duration is null`() {
        assertNull(rule.evaluate(context(sleepDurationMinutes = null)))
    }

    @Test
    fun `does not require illness onset when high strain short sleep and recovery strain are present`() {
        val context =
            InsightContext(
                today =
                    dailySummary(
                        recoveryFlags = emptySet(),
                        strainRatio = 1.4f,
                        sleepDurationMinutes = 360,
                        zLnHrv = -1.2f,
                    ),
                circadianResult = CircadianConsistencyResult.MissingData,
                goalSleepMinutes = 480,
            )

        val finding = HighStrainSleepDeficitRule().evaluate(context)

        assertEquals(InsightType.HIGH_STRAIN_SLEEP_DEFICIT, finding?.type)
    }
}
