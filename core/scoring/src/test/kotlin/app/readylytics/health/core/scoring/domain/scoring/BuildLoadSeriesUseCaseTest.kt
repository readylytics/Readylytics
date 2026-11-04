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
        // A single hard first workout pushes CTL above the floor; the history guard must still hold.
        val dailyTrimp = mapOf(targetDate to 300f)
        stubSeries(dailyTrimp, ctl = 13.9f, atl = 75f, sr = 5.4f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)

        val result = useCase.execute(targetDate, dailyTrimp, noEveryday, trackingStart = targetDate)

        assertEquals(100f, result.loadScore, 0.001f)
        assertEquals(100f, result.loadScoreEverydayHr, 0.001f)
        // The guard gates the load score only; the raw ratio stays untouched.
        assertEquals(5.4f, result.strainRatio, 0.001f)
        assertEquals(75f, result.atl, 0.001f)
        assertEquals(13.9f, result.ctl, 0.001f)
    }

    @Test
    fun `history of 28 days since true first data activates load score`() {
        val dailyTrimp = mapOf(targetDate to 20f)
        stubSeries(dailyTrimp, ctl = 6.0f, atl = 8.0f, sr = 1.33f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)
        every { calculator.computeLoadScore(1.33f) } returns 35.0f

        val result = useCase.execute(targetDate, dailyTrimp, noEveryday, targetDate.minusDays(27))

        assertEquals(35.0f, result.loadScore, 0.001f)
    }

    @Test
    fun `history of 27 days stays neutral`() {
        val dailyTrimp = mapOf(targetDate to 20f)
        stubSeries(dailyTrimp, ctl = 6.0f, atl = 8.0f, sr = 1.33f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)

        val result = useCase.execute(targetDate, dailyTrimp, noEveryday, targetDate.minusDays(26))

        assertEquals(100f, result.loadScore, 0.001f)
    }

    @Test
    fun `veteran with recent training gap is not treated as new`() {
        // Only 10 days of TRIMP survive in the 84-day window, but the user has years of history.
        val dailyTrimp = mapOf(targetDate.minusDays(10) to 600f)
        stubSeries(dailyTrimp, ctl = 12.0f, atl = 6.0f, sr = 0.5f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)
        every { calculator.computeLoadScore(0.5f) } returns 70.0f

        val result = useCase.execute(targetDate, dailyTrimp, noEveryday, targetDate.minusDays(900))

        assertEquals(70.0f, result.loadScore, 0.001f)
    }

    @Test
    fun `comeback session after a long layoff stays neutral because CTL is below the floor`() {
        // One 100-TRIMP session after >84 days off: CTL ~4.65, SR ~5.4 would score near zero.
        val dailyTrimp = mapOf(targetDate to 100f)
        stubSeries(dailyTrimp, ctl = 4.65f, atl = 25f, sr = 5.4f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)

        val result = useCase.execute(targetDate, dailyTrimp, noEveryday, targetDate.minusDays(365))

        assertEquals(100f, result.loadScore, 0.001f)
        // The raw ratio is still the physiological value.
        assertEquals(5.4f, result.strainRatio, 0.001f)
    }

    @Test
    fun `CTL exactly at the floor activates load score`() {
        val dailyTrimp = mapOf(targetDate to 100f)
        stubSeries(dailyTrimp, ctl = 5.0f, atl = 10f, sr = 2.0f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)
        every { calculator.computeLoadScore(2.0f) } returns 55.0f

        val result = useCase.execute(targetDate, dailyTrimp, noEveryday, targetDate.minusDays(365))

        assertEquals(55.0f, result.loadScore, 0.001f)
    }

    @Test
    fun `no data at all returns neutral load score`() {
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)

        val result = useCase.execute(targetDate, noEveryday, noEveryday, trackingStart = null)

        assertEquals(100f, result.loadScore, 0.001f)
        assertEquals(100f, result.loadScoreEverydayHr, 0.001f)
    }

    @Test
    fun `everyday HR load is scored from the shared tracking start while workout CTL is below floor`() {
        val everydayTrimp = mapOf(targetDate to 120f)
        stubSeries(noEveryday, ctl = 0f, atl = 0f, sr = 0f)
        stubSeries(everydayTrimp, ctl = 6.5f, atl = 10.0f, sr = 1.54f)
        every { calculator.computeLoadScore(1.54f) } returns 85.0f

        val result = useCase.execute(targetDate, noEveryday, everydayTrimp, targetDate.minusDays(40))

        assertEquals(85.0f, result.loadScoreEverydayHr, 0.001f)
        assertEquals(100f, result.loadScore, 0.001f)
    }
}
