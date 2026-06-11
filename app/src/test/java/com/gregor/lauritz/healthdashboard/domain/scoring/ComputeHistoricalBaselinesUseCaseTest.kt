package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.scoring.strategies.LoadScoringStrategy
import com.gregor.lauritz.healthdashboard.domain.util.stdev
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [ComputeHistoricalBaselinesUseCase]. The per-day window math (no look-ahead,
 * exclusion of the day's own session, validity gating, percentile nadirs) now lives in
 * [BaselineComputer.computeBackfillBaselines] and is covered — together with byte-for-byte
 * equivalence against the per-day methods — by [BaselineComputerBackfillEquivalenceTest]. These
 * tests cover the use-case-level mapping/snapshot logic over the batched results.
 */
class ComputeHistoricalBaselinesUseCaseTest {
    private val baselineComputer = mockk<BaselineComputer>()
    private val loadScoringStrategy = mockk<LoadScoringStrategy>()
    private val useCase = ComputeHistoricalBaselinesUseCase(baselineComputer, loadScoringStrategy)

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun midnightMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun fakeSummary(date: LocalDate): DailySummaryEntity = DailySummaryEntity(dateMidnightMs = midnightMs(date))

    private fun fakeBaseline(
        mu: List<Float> = listOf(50f, 52f),
        sigma: List<Float> = listOf(5f),
        rhr: Float = 60f,
        rhrHistory: List<Int> = emptyList(),
    ) = BaselineComputer.BackfillBaseline(
        muHistory = mu,
        sigmaHistory = sigma,
        rhrBpm = rhr,
        rhrHistory = rhrHistory,
    )

    @Test
    fun `all 5 dates processed — no 30-day cutoff`() =
        runTest {
            val day1 = LocalDate.of(2026, 1, 1)
            val summaries = (0..4).map { fakeSummary(day1.plusDays(it.toLong())) }

            coEvery { baselineComputer.computeBackfillBaselines(any(), any()) } returns
                summaries.associate { it.dateMidnightMs to fakeBaseline() }
            every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = useCase.computeHistoricalBaselines(summaries, UserPreferences())

            assertEquals(5, result.size)
        }

    @Test
    fun `restingHrPercentile is forwarded to the batched computation`() =
        runTest {
            val summary = fakeSummary(LocalDate.of(2026, 1, 1))
            val percentileSlot = slot<Int>()
            coEvery { baselineComputer.computeBackfillBaselines(any(), capture(percentileSlot)) } returns
                mapOf(summary.dateMidnightMs to fakeBaseline())
            every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            useCase.computeHistoricalBaselines(listOf(summary), UserPreferences(restingHrPercentile = 9))

            assertEquals(9, percentileSlot.captured)
        }

    @Test
    fun `profile lnSigmaPrior flows to sigma computation and snapshot`() =
        runTest {
            val summary = fakeSummary(LocalDate.of(2026, 1, 1))

            coEvery { baselineComputer.computeBackfillBaselines(any(), any()) } returns
                mapOf(summary.dateMidnightMs to fakeBaseline())

            val capturedSigmaPrior = slot<Float>()
            every { loadScoringStrategy.hrvSigma(any(), capture(capturedSigmaPrior)) } returns 0.10f

            val athletePrefs = UserPreferences(physiologyProfile = PhysiologyProfile.ATHLETE)
            val result = useCase.computeHistoricalBaselines(listOf(summary), athletePrefs)

            assertNotNull(result.firstOrNull())
            assertEquals(PhysiologyProfile.ATHLETE.lnSigmaPrior, capturedSigmaPrior.captured)
            assertEquals(PhysiologyProfile.ATHLETE.name, result.first().snapshotProfile)
            assertEquals(PhysiologyProfile.ATHLETE.lnSigmaPrior, result.first().hrvSigmaPrior)
        }

    @Test
    fun `observation count and rhr baseline pass through from the batched result`() =
        runTest {
            val summary = fakeSummary(LocalDate.of(2026, 1, 1))
            coEvery { baselineComputer.computeBackfillBaselines(any(), any()) } returns
                mapOf(summary.dateMidnightMs to fakeBaseline(mu = listOf(48f, 49f, 51f), rhr = 57f))
            every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = useCase.computeHistoricalBaselines(listOf(summary), UserPreferences())

            assertEquals(3, result.first().baselineObservationCount)
            assertEquals(57f, result.first().rhrBpm)
        }

    @Test
    fun `rhr sigma is snapshotted from the same RHR history used by live scoring`() =
        runTest {
            val summary = fakeSummary(LocalDate.of(2026, 1, 1))
            val rhrHistory = listOf(52, 54, 56, 58)
            coEvery { baselineComputer.computeBackfillBaselines(any(), any()) } returns
                mapOf(summary.dateMidnightMs to fakeBaseline(rhrHistory = rhrHistory))
            every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = useCase.computeHistoricalBaselines(listOf(summary), UserPreferences())

            assertEquals(rhrHistory.stdev(), result.first().rhrSigma)
        }

    @Test
    fun `day with empty mu history is skipped`() =
        runTest {
            val withData = fakeSummary(LocalDate.of(2026, 1, 1))
            val noData = fakeSummary(LocalDate.of(2026, 1, 2))
            coEvery { baselineComputer.computeBackfillBaselines(any(), any()) } returns
                mapOf(
                    withData.dateMidnightMs to fakeBaseline(),
                    noData.dateMidnightMs to fakeBaseline(mu = emptyList(), sigma = emptyList()),
                )
            every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = useCase.computeHistoricalBaselines(listOf(withData, noData), UserPreferences())

            assertEquals(1, result.size)
            assertEquals(withData.dateMidnightMs, result.first().dateMidnightMs)
        }

    @Test
    fun `already-frozen summaries are not pre-skipped by the use case`() =
        runTest {
            val date = LocalDate.of(2026, 1, 1)
            val frozen = DailySummaryEntity(dateMidnightMs = midnightMs(date), baselineCalculatedAtDate = date)

            coEvery { baselineComputer.computeBackfillBaselines(any(), any()) } returns
                mapOf(frozen.dateMidnightMs to fakeBaseline())
            every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = useCase.computeHistoricalBaselines(listOf(frozen), UserPreferences())

            assertEquals(1, result.size)
        }

    @Test
    fun `empty input returns empty without invoking the computer`() =
        runTest {
            val result = useCase.computeHistoricalBaselines(emptyList(), UserPreferences())
            assertEquals(0, result.size)
        }
}
