package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighStrainSleepDeficitRuleTest {
    private val rule = HighStrainSleepDeficitRule()

    private fun context(
        recoveryFlags: Set<RecoveryFlag> = setOf(RecoveryFlag.ILLNESS_ONSET),
        strainRatio: Float? = 1.5f,
        sleepDurationMinutes: Int? = 360,
        goalSleepMinutes: Int = 480,
    ) = InsightContext(
        today =
            dailySummary(
                recoveryFlags = recoveryFlags,
                strainRatio = strainRatio,
                sleepDurationMinutes = sleepDurationMinutes,
            ),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = goalSleepMinutes,
    )

    @Test
    fun `fires when illness onset, high strain ratio and sleep deficit all hold`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.HIGH_STRAIN_SLEEP_DEFICIT, finding?.type)
        assertEquals(
            InsightParams.HighStrainSleepDeficit(strainRatio = 1.5f, sleepDeficitMinutes = 120),
            finding?.params,
        )
    }

    @Test
    fun `does not fire without illness onset flag`() {
        assertNull(rule.evaluate(context(recoveryFlags = emptySet())))
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
}
