package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightEngineTest {
    @Test
    fun `empty summary produces no findings`() {
        val context =
            InsightContext(
                today = dailySummary(),
                circadianResult = CircadianConsistencyResult.MissingData,
                goalSleepMinutes = 480,
            )

        assertTrue(InsightEngine.evaluate(context).isEmpty())
    }

    @Test
    fun `multiple rules can fire together`() {
        val context =
            InsightContext(
                today =
                    dailySummary(
                        recoveryFlags = setOf(RecoveryFlag.REST_DAY_NO_IMPACT, RecoveryFlag.ILLNESS_ONSET),
                        strainRatio = 1.5f,
                        sleepDurationMinutes = 360,
                        lateNadir = true,
                    ),
                circadianResult = circadianReady(latestBedtimeOffsetMinutes = 105),
                goalSleepMinutes = 480,
            )

        val findings = InsightEngine.evaluate(context)

        assertEquals(3, findings.size)
        assertEquals(
            setOf(
                InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS,
                InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
                InsightType.LATE_NADIR_SHORT_SLEEP,
            ),
            findings.map { it.type }.toSet(),
        )
    }
}
