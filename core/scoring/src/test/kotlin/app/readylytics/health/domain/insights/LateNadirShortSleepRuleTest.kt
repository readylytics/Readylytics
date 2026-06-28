package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LateNadirShortSleepRuleTest {
    private val rule = LateNadirShortSleepRule()

    private fun context(
        lateNadir: Boolean = true,
        sleepDurationMinutes: Int? = 360,
        goalSleepMinutes: Int = 480,
    ) = InsightContext(
        today = dailySummary(sleepDurationMinutes = sleepDurationMinutes, lateNadir = lateNadir),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = goalSleepMinutes,
    )

    @Test
    fun `fires when late nadir and sleep below deficit threshold`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.LATE_NADIR_SHORT_SLEEP, finding?.type)
        assertEquals(
            InsightParams.LateNadirShortSleep(sleepDurationMinutes = 360, goalSleepMinutes = 480),
            finding?.params,
        )
    }

    @Test
    fun `does not fire when lateNadir is false`() {
        assertNull(rule.evaluate(context(lateNadir = false)))
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
