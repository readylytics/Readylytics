package app.readylytics.health.core.scoring.domain.scoring

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class BuildLoadSeriesUseCaseTest {
    private val calculator = mockk<ScoringCalculator>()
    private val useCase = BuildLoadSeriesUseCase(calculator)
    private val targetDate = LocalDate.of(2026, 5, 10)
    private val noEveryday = emptyMap<LocalDate, Float>()

    private fun stubSeries(
        trimp: Map<LocalDate, Float>,
        ctl: Float,
        atl: Float,
        sr: Float,
    ) {
        every { calculator.computeCtlEmaWithDecay(trimp, targetDate) } returns ctl
        every { calculator.computeAtlEmaWithDecay(trimp, targetDate) } returns atl
        every { calculator.computeStrainRatio(atl, ctl) } returns sr
    }

    @Test
    fun `short history returns neutral load score even when CTL is high`() {
        // A single hard first workout pushes CTL above 5; the guard must still hold.
        val dailyTrimp = mapOf(targetDate to 300f)
        stubSeries(dailyTrimp, ctl = 13.9f, atl = 75f, sr = 5.4f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)

        val result =
            useCase.execute(
                targetDate,
                dailyTrimp,
                noEveryday,
                BuildLoadSeriesUseCase.HistoryStart(workoutOnly = targetDate, everydayHr = null),
            )

        assertEquals(100f, result.loadScore, 0.001f)
        assertEquals(100f, result.loadScoreEverydayHr, 0.001f)
        // The guard gates the load score only; the raw ratio stays untouched.
        assertEquals(5.4f, result.strainRatio, 0.001f)
        assertEquals(75f, result.atl, 0.001f)
        assertEquals(13.9f, result.ctl, 0.001f)
    }

    @Test
    fun `history of 28 days since true first workout activates load score`() {
        val dailyTrimp = mapOf(targetDate to 20f)
        stubSeries(dailyTrimp, ctl = 3.0f, atl = 8.0f, sr = 2.67f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)
        every { calculator.computeLoadScore(2.67f) } returns 35.0f

        val result =
            useCase.execute(
                targetDate,
                dailyTrimp,
                noEveryday,
                BuildLoadSeriesUseCase.HistoryStart(workoutOnly = targetDate.minusDays(27), everydayHr = null),
            )

        assertEquals(35.0f, result.loadScore, 0.001f)
    }

    @Test
    fun `history of 27 days stays neutral`() {
        val dailyTrimp = mapOf(targetDate to 20f)
        stubSeries(dailyTrimp, ctl = 3.0f, atl = 8.0f, sr = 2.67f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)

        val result =
            useCase.execute(
                targetDate,
                dailyTrimp,
                noEveryday,
                BuildLoadSeriesUseCase.HistoryStart(workoutOnly = targetDate.minusDays(26), everydayHr = null),
            )

        assertEquals(100f, result.loadScore, 0.001f)
    }

    @Test
    fun `veteran with recent training gap is not treated as new`() {
        // Only 10 days of TRIMP survive in the 84-day window, but the user has years of history.
        val dailyTrimp = mapOf(targetDate.minusDays(10) to 60f)
        stubSeries(dailyTrimp, ctl = 2.0f, atl = 1.0f, sr = 0.5f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)
        every { calculator.computeLoadScore(0.5f) } returns 70.0f

        val result =
            useCase.execute(
                targetDate,
                dailyTrimp,
                noEveryday,
                BuildLoadSeriesUseCase.HistoryStart(workoutOnly = targetDate.minusDays(900), everydayHr = null),
            )

        assertEquals(70.0f, result.loadScore, 0.001f)
    }

    @Test
    fun `no data at all returns neutral load score`() {
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)

        val result =
            useCase.execute(
                targetDate,
                noEveryday,
                noEveryday,
                BuildLoadSeriesUseCase.HistoryStart(workoutOnly = null, everydayHr = null),
            )

        assertEquals(100f, result.loadScore, 0.001f)
        assertEquals(100f, result.loadScoreEverydayHr, 0.001f)
    }

    @Test
    fun `everyday HR guard is independent from workout guard`() {
        val everydayTrimp = mapOf(targetDate to 120f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)
        stubSeries(everydayTrimp, ctl = 6.5f, atl = 10.0f, sr = 1.54f)
        every { calculator.computeLoadScore(1.54f) } returns 85.0f

        val result =
            useCase.execute(
                targetDate,
                noEveryday,
                everydayTrimp,
                BuildLoadSeriesUseCase.HistoryStart(workoutOnly = null, everydayHr = targetDate.minusDays(40)),
            )

        assertEquals(85.0f, result.loadScoreEverydayHr, 0.001f)
        assertEquals(100f, result.loadScore, 0.001f)
    }
}
