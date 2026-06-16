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
                        zLnHrv = -1.2f,
                    ),
                circadianResult = circadianReady(latestBedtimeOffsetMinutes = 105),
                goalSleepMinutes = 480,
            )

        val findings = InsightEngine.evaluate(context)

        assertEquals(4, findings.size)
        assertEquals(
            setOf(
                InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS,
                InsightType.LOAD_SPIKE_RECOVERY_STRAIN,
                InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
                InsightType.LATE_NADIR_SHORT_SLEEP,
            ),
            findings.map { it.type }.toSet(),
        )
    }

    @Test
    fun `all registered rules can fire together`() {
        val today =
            dailySummary(
                recoveryFlags =
                    setOf(
                        RecoveryFlag.REST_DAY_NO_IMPACT,
                        RecoveryFlag.ILLNESS_ONSET,
                        RecoveryFlag.HRV_MISSING,
                        RecoveryFlag.STAGES_MISSING,
                    ),
                strainRatio = 2f,
                sleepDurationMinutes = 300,
                lateNadir = true,
                rhrDeltaBpm = 10f,
                zLnHrv = -2f,
                avgSleepingSpo2 = 90f,
                bloodPressureSystolic = 140,
                legacyRasScore = 10f,
                legacyTotalRas = 5f,
                stepCount = 1000,
                weightKg = 85f,
            )
        val context =
            InsightContext(
                today = today,
                circadianResult = circadianReady(latestBedtimeOffsetMinutes = 105),
                goalSleepMinutes = 480,
                stepGoal = 10000,
                recentDays =
                    (1..21).map { offset ->
                        dailySummary(
                            date = today.date.minusDays(offset.toLong()),
                            zLnHrv = -1f,
                            bloodPressureSystolic = 110,
                            legacyTotalRas = 5f,
                            weightKg = 80f,
                            totalTrimp = 60f,
                        )
                    },
            )

        val findings = InsightEngine.evaluate(context)
        val firedTypes = findings.map { it.type }.toSet()

        assertEquals(
            setOf(
                InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS,
                InsightType.LOAD_SPIKE_RECOVERY_STRAIN,
                InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
                InsightType.LATE_NADIR_SHORT_SLEEP,
                InsightType.RECOVERY_HRV_MISSING,
                InsightType.RECOVERY_STAGES_MISSING,
                InsightType.HRV_DROP_LOW_SPO2,
                InsightType.LATE_NADIR_ELEVATED_RHR,
                InsightType.BP_ELEVATED_HIGH_STRAIN,
                InsightType.RAS_DEPLETION_HIGH_STRAIN,
                InsightType.HRV_DECLINE_STREAK,
                InsightType.STEP_SHORTFALL,
                InsightType.RAS_WEEKLY_UNDERPERFORMANCE,
                InsightType.WEIGHT_DRIFT_TRAINING_LOAD,
            ),
            firedTypes,
        )
    }
}
