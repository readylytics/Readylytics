package app.readylytics.health.domain.insights.detail

import app.readylytics.health.domain.model.InsightType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class InsightCauseRankerTest {
    private val ranker = InsightCauseRanker()

    @Test
    fun `late workout ranks first when workout ended within four hours before sleep`() {
        val causes = causes(CauseRankHint.POOR_SLEEP, CauseRankHint.LATE_WORKOUT)
        val ranked =
            ranker.rankCauses(
                insightId = InsightType.LATE_NADIR,
                context = context(lastWorkoutEndedMinutesBeforeSleep = 180),
                causes = causes,
            )

        assertEquals(CauseRankHint.LATE_WORKOUT, ranked.first().rankHint)
    }

    @Test
    fun `high strain ranks first when strain ratio is high`() {
        val causes = causes(CauseRankHint.POOR_SLEEP, CauseRankHint.HIGH_STRAIN_RATIO)
        val ranked =
            ranker.rankCauses(
                insightId = InsightType.LOAD_SPIKE_RECOVERY_STRAIN,
                context = context(strainRatio = 1.4f),
                causes = causes,
            )

        assertEquals(CauseRankHint.HIGH_STRAIN_RATIO, ranked.first().rankHint)
    }

    @Test
    fun `low sleep ranks first when sleep below eighty five percent of goal`() {
        val causes = causes(CauseRankHint.HIGH_TRIMP_YESTERDAY, CauseRankHint.POOR_SLEEP)
        val ranked =
            ranker.rankCauses(
                insightId = InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
                context = context(sleepDurationMinutes = 360, goalSleepMinutes = 480),
                causes = causes,
            )

        assertEquals(CauseRankHint.POOR_SLEEP, ranked.first().rankHint)
    }

    @Test
    fun `low SpO2 ranks first for HRV drop low SpO2`() {
        val causes = causes(CauseRankHint.POOR_SLEEP, CauseRankHint.LOW_SPO2)
        val ranked =
            ranker.rankCauses(
                insightId = InsightType.HRV_DROP_LOW_SPO2,
                context = context(avgSleepingSpo2 = 93.5f),
                causes = causes,
            )

        assertEquals(CauseRankHint.LOW_SPO2, ranked.first().rankHint)
    }

    @Test
    fun `same input returns same order`() {
        val causes =
            causes(
                CauseRankHint.GENERIC,
                CauseRankHint.ELEVATED_RHR,
                CauseRankHint.LOW_HRV,
            )
        val input = context(rhrDeltaBpm = 5f, zLnHrv = -1.2f)

        val first = ranker.rankCauses(InsightType.SICK_INDICATOR, input, causes)
        val second = ranker.rankCauses(InsightType.SICK_INDICATOR, input, causes)

        assertEquals(first, second)
    }

    private fun causes(vararg hints: CauseRankHint) =
        hints.mapIndexed { index, hint ->
            InsightCause(
                title = "cause-$index",
                description = "description-$index",
                rankHint = hint,
            )
        }

    private fun context(
        sleepDurationMinutes: Int? = null,
        goalSleepMinutes: Int? = null,
        zLnHrv: Float? = null,
        zRhr: Float? = null,
        rhrDeltaBpm: Float? = null,
        strainRatio: Float? = null,
        avgSleepingSpo2: Float? = null,
        lastWorkoutEndedMinutesBeforeSleep: Int? = null,
    ) = DailyInsightContext(
        date = LocalDate.of(2026, 6, 15),
        sleepScore = null,
        sleepDurationMinutes = sleepDurationMinutes,
        goalSleepMinutes = goalSleepMinutes,
        zLnHrv = zLnHrv,
        zRhr = zRhr,
        rhrDeltaBpm = rhrDeltaBpm,
        readinessScore = null,
        yesterdayTrimp = null,
        strainRatio = strainRatio,
        acute7dLoad = null,
        chronic28dLoad = null,
        stepCount = null,
        stepGoal = null,
        bloodPressureSystolic = null,
        bloodPressureBaselineSystolic = null,
        avgSleepingSpo2 = avgSleepingSpo2,
        weightKg = null,
        previousWeightKg = null,
        bedtimeOffsetMinutes = null,
        lastWorkoutEndedMinutesBeforeSleep = lastWorkoutEndedMinutesBeforeSleep,
        workoutDurationMinutes = null,
        workoutIntensityCategory = null,
    )
}
