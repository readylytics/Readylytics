package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class WeightDriftTrainingLoadRuleTest {
    private val rule = WeightDriftTrainingLoadRule()
    private val today = LocalDate.of(2026, 6, 12)

    private fun context(
        todayWeightKg: Float? = 81f,
        strainRatio: Float? = 1.5f,
        recentDays: List<DailySummary> =
            listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
    ) = InsightContext(
        today = dailySummary(date = today, weightKg = todayWeightKg, strainRatio = strainRatio),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        recentDays = recentDays,
    )

    @Test
    fun `fires when weight drift exceeds threshold and strain ratio is high`() {
        val finding =
            rule.evaluate(
                context(
                    todayWeightKg = 82f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            )

        // delta = 2, percent = 2/80 x 100 = 2.5 > 2.0
        assertEquals(InsightType.WEIGHT_DRIFT_TRAINING_LOAD, finding?.type)
        assertEquals(InsightParams.WeightDrift(deltaKg = 2f, percent = 2.5f), finding?.params)
    }

    @Test
    fun `does not fire when today weight is null`() {
        assertNull(rule.evaluate(context(todayWeightKg = null)))
    }

    @Test
    fun `does not fire when no other day has a weight reading`() {
        assertNull(rule.evaluate(context(recentDays = listOf(dailySummary(date = today.minusDays(1))))))
    }

    @Test
    fun `does not fire when recentDays is empty`() {
        assertNull(rule.evaluate(context(recentDays = emptyList())))
    }

    @Test
    fun `does not fire when percent drift is at threshold`() {
        // delta = 1.6, percent = 1.6 / 80 x 100 = 2.0 (exactly threshold)
        assertNull(
            rule.evaluate(
                context(
                    todayWeightKg = 81.6f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            ),
        )
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
    fun `fires for weight loss drift as well as gain`() {
        val finding =
            rule.evaluate(
                context(
                    todayWeightKg = 78f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            )

        // delta = -2, percent = abs(-2)/80 x 100 = 2.5 > 2.0
        assertEquals(InsightType.WEIGHT_DRIFT_TRAINING_LOAD, finding?.type)
        assertEquals(InsightParams.WeightDrift(deltaKg = -2f, percent = 2.5f), finding?.params)
    }

    @Test
    fun `oldest weight reading in window is used as baseline`() {
        val recentDays =
            listOf(
                dailySummary(date = today.minusDays(1), weightKg = 81.9f),
                dailySummary(date = today.minusDays(6), weightKg = 80f),
            )
        val finding = rule.evaluate(context(todayWeightKg = 82f, recentDays = recentDays))

        // baseline should be the oldest (80f), delta = 2, percent = 2.5
        assertEquals(InsightParams.WeightDrift(deltaKg = 2f, percent = 2.5f), finding?.params)
    }

    @Test
    fun `percent param formats as a real percentage through the presentation path`() {
        val finding =
            rule.evaluate(
                context(
                    todayWeightKg = 82f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            )
        val params = finding?.params as InsightParams.WeightDrift

        val body =
            "Your weight has changed by %1$.1f kg (%2$.1f%%) over the past week while your training load is high"
        val rendered = String.format(Locale.US, body, params.deltaKg, params.percent)

        assertTrue("expected a real percentage, got: $rendered", rendered.contains("(2.5%)"))
        assertFalse("fraction scale would render 0.0%, got: $rendered", rendered.contains("0.0%"))
    }
}
