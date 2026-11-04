package app.readylytics.health.core.scoring.domain.scoring

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BuildLoadSeriesUseCaseTest {
    private val calculator = mockk<ScoringCalculator>()
    private val useCase = BuildLoadSeriesUseCase(calculator)
    private val targetDate = LocalDate.of(2026, 5, 10)

    @Test
    fun `first workout with low CTL returns neutral load score and insufficient history flag`() {
        val dailyTrimp = mapOf(targetDate to 100f)
        val everydayTrimp = emptyMap<LocalDate, Float>()

        every { calculator.computeCtlEmaWithDecay(dailyTrimp, targetDate) } returns 4.65f
        every { calculator.computeAtlEmaWithDecay(dailyTrimp, targetDate) } returns 25.0f
        every { calculator.computeStrainRatio(25.0f, 4.65f) } returns 5.376f

        every { calculator.computeCtlEmaWithDecay(everydayTrimp, targetDate) } returns 0f
        every { calculator.computeAtlEmaWithDecay(everydayTrimp, targetDate) } returns 0f
        every { calculator.computeStrainRatio(0f, 0f) } returns 0f

        val result = useCase.execute(targetDate, dailyTrimp, everydayTrimp)

        // Low CTL (< 5.0) and short history (< 28 days) -> neutral Load = 100
        assertEquals(100f, result.loadScore, 0.001f)
        assertFalse(result.hasSufficientHistoryWorkoutOnly)
        assertFalse(result.hasSufficientHistoryEverydayHr)
    }

    @Test
    fun `history of 28 or more days activates ACWR load score`() {
        val firstDate = targetDate.minusDays(30)
        val dailyTrimp = mapOf(firstDate to 50f, targetDate to 20f)
        val everydayTrimp = emptyMap<LocalDate, Float>()

        every { calculator.computeCtlEmaWithDecay(dailyTrimp, targetDate) } returns 3.0f
        every { calculator.computeAtlEmaWithDecay(dailyTrimp, targetDate) } returns 8.0f
        every { calculator.computeStrainRatio(8.0f, 3.0f) } returns 2.67f
        every { calculator.computeLoadScore(2.67f) } returns 35.0f

        every { calculator.computeCtlEmaWithDecay(everydayTrimp, targetDate) } returns 0f
        every { calculator.computeAtlEmaWithDecay(everydayTrimp, targetDate) } returns 0f
        every { calculator.computeStrainRatio(0f, 0f) } returns 0f

        val result = useCase.execute(targetDate, dailyTrimp, everydayTrimp)

        assertEquals(35.0f, result.loadScore, 0.001f)
        assertTrue(result.hasSufficientHistoryWorkoutOnly)
    }

    @Test
    fun `CTL above floor activates ACWR load score even under 28 days`() {
        val firstDate = targetDate.minusDays(10)
        val dailyTrimp = mapOf(firstDate to 100f, targetDate to 100f)
        val everydayTrimp = emptyMap<LocalDate, Float>()

        every { calculator.computeCtlEmaWithDecay(dailyTrimp, targetDate) } returns 12.0f
        every { calculator.computeAtlEmaWithDecay(dailyTrimp, targetDate) } returns 25.0f
        every { calculator.computeStrainRatio(25.0f, 12.0f) } returns 2.08f
        every { calculator.computeLoadScore(2.08f) } returns 22.0f

        every { calculator.computeCtlEmaWithDecay(everydayTrimp, targetDate) } returns 0f
        every { calculator.computeAtlEmaWithDecay(everydayTrimp, targetDate) } returns 0f
        every { calculator.computeStrainRatio(0f, 0f) } returns 0f

        val result = useCase.execute(targetDate, dailyTrimp, everydayTrimp)

        assertEquals(22.0f, result.loadScore, 0.001f)
        assertTrue(result.hasSufficientHistoryWorkoutOnly)
    }

    @Test
    fun `everyday HR history guard activates ACWR load score when history 28 days or CTL above floor`() {
        val dailyTrimp = emptyMap<LocalDate, Float>()
        val firstDate = targetDate.minusDays(29)
        val everydayTrimp = mapOf(firstDate to 150f, targetDate to 120f)

        every { calculator.computeCtlEmaWithDecay(dailyTrimp, targetDate) } returns 0f
        every { calculator.computeAtlEmaWithDecay(dailyTrimp, targetDate) } returns 0f
        every { calculator.computeStrainRatio(0f, 0f) } returns 0f

        every { calculator.computeCtlEmaWithDecay(everydayTrimp, targetDate) } returns 6.5f
        every { calculator.computeAtlEmaWithDecay(everydayTrimp, targetDate) } returns 10.0f
        every { calculator.computeStrainRatio(10.0f, 6.5f) } returns 1.54f
        every { calculator.computeLoadScore(1.54f) } returns 85.0f

        val result = useCase.execute(targetDate, dailyTrimp, everydayTrimp)

        assertEquals(85.0f, result.loadScoreEverydayHr, 0.001f)
        assertTrue(result.hasSufficientHistoryEverydayHr)
        assertFalse(result.hasSufficientHistoryWorkoutOnly)
    }
}
