package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.scoring.strategies.LoadScoringStrategy
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

class ComputeHistoricalBaselinesUseCaseTest {

    private val baselineComputer = mockk<BaselineComputer>()
    private val loadScoringStrategy = mockk<LoadScoringStrategy>()
    private val useCase = ComputeHistoricalBaselinesUseCase(baselineComputer, loadScoringStrategy)

    private fun fakeSummary(date: LocalDate): DailySummaryEntity {
        val zone = ZoneId.systemDefault()
        return DailySummaryEntity(dateMidnightMs = date.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    private fun fakeWindows() =
        BaselineComputer.HrvWindows(
            muHistory = listOf(50f, 52f),
            sigmaHistory = listOf(5f),
            historicalSessions = emptyList(),
            validHistoricalSessionIds = emptyList(),
        )

    @Test
    fun `all 5 dates processed — no 30-day cutoff`() = runTest {
        val day1 = LocalDate.of(2026, 1, 1)
        val summaries = (0..4).map { fakeSummary(day1.plusDays(it.toLong())) }

        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any()) } returns fakeWindows()
        coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any()) } returns 60f
        every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

        val result = useCase.computeHistoricalBaselines(summaries, UserPreferences())

        assertEquals(5, result.size)
    }

    @Test
    fun `day 5 window upper bound is end-of-day for date D — no look-ahead`() = runTest {
        val zone = ZoneId.systemDefault()
        val day1 = LocalDate.of(2026, 1, 1)
        val summaries = (0..4).map { fakeSummary(day1.plusDays(it.toLong())) }

        val capturedUpperBounds = mutableListOf<Long>()

        coEvery {
            baselineComputer.computeHrvWindowsBetween(any(), any(), any())
        } answers {
            capturedUpperBounds += secondArg<Long>()
            fakeWindows()
        }
        coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any()) } returns 60f
        every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

        useCase.computeHistoricalBaselines(summaries, UserPreferences())

        assertEquals(5, capturedUpperBounds.size)

        // Day 5 (index 4) upper bound = midnight(day 6) - 1ms
        val day5 = day1.plusDays(4)
        val expectedEndMs = day5.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        assertEquals(expectedEndMs, capturedUpperBounds[4])
    }

    @Test
    fun `profile lnSigmaPrior flows to sigma computation and snapshot`() = runTest {
        val summary = fakeSummary(LocalDate.of(2026, 1, 1))

        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any()) } returns fakeWindows()
        coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any()) } returns 60f

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
    fun `already-frozen summaries are not skipped during historical compute`() = runTest {
        val date = LocalDate.of(2026, 1, 1)
        val zone = ZoneId.systemDefault()
        val ms = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val frozen = DailySummaryEntity(dateMidnightMs = ms, baselineCalculatedAtDate = date)

        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any()) } returns fakeWindows()
        coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any()) } returns 60f
        every { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

        val result = useCase.computeHistoricalBaselines(listOf(frozen), UserPreferences())

        assertEquals(1, result.size)
    }
}
