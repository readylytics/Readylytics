package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class LoadSpikeRecoveryStrainRuleTest {
    private val rule = LoadSpikeRecoveryStrainRule()

    private fun context(
        today: DailySummary,
        recentDays: List<DailySummary> = emptyList(),
        goalSleepMinutes: Int = 480,
    ) = InsightContext(
        today = today,
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = goalSleepMinutes,
        recentDays = recentDays,
    )

    @Test
    fun `emits when strain ratio is high and HRV is low`() {
        val context =
            context(
                today =
                    dailySummary(
                        strainRatio = 1.4f,
                        zLnHrv = -1.1f,
                    ),
                recentDays = loadHistory(days = 21, trimp = 60f),
            )

        val finding = rule.evaluate(context)

        assertEquals(InsightType.LOAD_SPIKE_RECOVERY_STRAIN, finding?.type)
    }

    @Test
    fun `does not emit with load spike but no recovery strain`() {
        val context =
            context(
                today =
                    dailySummary(
                        strainRatio = 1.4f,
                        zLnHrv = 0.2f,
                        zRhr = 0.1f,
                        readinessScore = 80f,
                        sleepDurationMinutes = 480,
                    ),
                recentDays = loadHistory(days = 21, trimp = 60f),
                goalSleepMinutes = 480,
            )

        assertNull(rule.evaluate(context))
    }

    @Test
    fun `does not emit with recovery strain but no load spike`() {
        val context =
            context(
                today =
                    dailySummary(
                        strainRatio = 1.0f,
                        totalTrimp = 50f,
                        zLnHrv = -1.2f,
                    ),
                recentDays = loadHistory(days = 21, trimp = 60f),
            )

        assertNull(rule.evaluate(context))
    }

    @Test
    fun `emits from high yesterday TRIMP and elevated RHR`() {
        val todayDate = LocalDate.of(2026, 6, 12)
        val context =
            context(
                today =
                    dailySummary(
                        date = todayDate,
                        zRhr = 1.2f,
                    ),
                recentDays =
                    listOf(dailySummary(date = todayDate.minusDays(1), totalTrimp = 125f)) +
                        loadHistory(days = 21, startOffset = 2, trimp = 60f),
            )

        val finding = rule.evaluate(context)

        assertEquals(InsightType.LOAD_SPIKE_RECOVERY_STRAIN, finding?.type)
    }

    private fun loadHistory(
        days: Int,
        startOffset: Int = 1,
        trimp: Float,
    ) = (startOffset until startOffset + days).map { offset ->
        dailySummary(date = LocalDate.of(2026, 6, 12).minusDays(offset.toLong()), totalTrimp = trimp)
    }
}
