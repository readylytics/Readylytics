package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.feature.dashboard.usecase.LiveResidualFatigue
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/** Live residual fatigue: today-vs-snapshot selection, decay cadence, and failure isolation. */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelResidualFatigueTest : DashboardViewModelTestBase() {
    @Test
    fun `dashboard queries current residual fatigue and forwards it to dashboard data use case`() =
        runTest(testDispatcher) {
            val summary = DailySummary(date = LocalDate.of(2026, 7, 29))
            coEvery { getCurrentResidualFatigueUseCase(any(), any()) } returns LiveResidualFatigue.Value(0.35f)
            val selectedDate =
                configureDashboardFlows(
                    isSyncing = MutableStateFlow(false),
                    recalcProgress = MutableStateFlow(null),
                    summary = summary,
                )

            viewModel.uiState.first { it.summary == summary }

            coVerify(exactly = 1) {
                getCurrentResidualFatigueUseCase(selectedDate, ZoneId.of("UTC"))
            }
            verify {
                getDashboardDataUseCase.invoke(
                    summary = summary,
                    prefs = any(),
                    date = selectedDate,
                    lastSleepSession = any(),
                    rasSummaries = any(),
                    circadianResult = any(),
                    heartRateSummary = any(),
                    todayStrainIncrease = any(),
                    todayRasIncrease = any(),
                    bodyTempBaseline = any(),
                    liveResidualFatigue = LiveResidualFatigue.Value(0.35f),
                )
            }
        }

    // Regression: the memo used to key on (date, prefs, summary) only, so an idle dashboard —
    // one where no sync produces a new DailySummary — pinned the card to the value read when it
    // was opened and never re-decayed it. The minute bucket is what unpins it.
    @Test
    fun `live residual fatigue is recomputed when the minute bucket advances`() =
        runTest(testDispatcher) {
            val summary = DailySummary(date = LocalDate.of(2026, 7, 29))
            val buckets = MutableStateFlow(0L)
            every { fatigueTicker.minuteBuckets() } returns buckets
            coEvery { getCurrentResidualFatigueUseCase(any(), any()) } returns LiveResidualFatigue.Value(0.35f)
            configureDashboardFlows(
                isSyncing = MutableStateFlow(false),
                recalcProgress = MutableStateFlow(null),
                summary = summary,
            )

            viewModel.uiState.first { it.summary == summary }
            coEvery { getCurrentResidualFatigueUseCase(any(), any()) } returns LiveResidualFatigue.Value(0.21f)
            buckets.value = 1L
            advanceUntilIdle()

            coVerify(exactly = 2) { getCurrentResidualFatigueUseCase(any(), any()) }
            verify {
                getDashboardDataUseCase.invoke(
                    summary = any(),
                    prefs = any(),
                    date = any(),
                    lastSleepSession = any(),
                    rasSummaries = any(),
                    circadianResult = any(),
                    heartRateSummary = any(),
                    todayStrainIncrease = any(),
                    todayRasIncrease = any(),
                    bodyTempBaseline = any(),
                    liveResidualFatigue = LiveResidualFatigue.Value(0.21f),
                )
            }
        }

    // The memo has to keep absorbing the high-frequency data flows, otherwise every one of their
    // emissions re-runs computeCurrentResidualFatigue's unbounded workout-table scan.
    @Test
    fun `live residual fatigue is memoized within a single minute bucket`() =
        runTest(testDispatcher) {
            val summary = DailySummary(date = LocalDate.of(2026, 7, 29))
            val isSyncing = MutableStateFlow(false)
            coEvery { getCurrentResidualFatigueUseCase(any(), any()) } returns LiveResidualFatigue.Value(0.35f)
            configureDashboardFlows(
                isSyncing = isSyncing,
                recalcProgress = MutableStateFlow(null),
                summary = summary,
            )

            viewModel.uiState.first { it.summary == summary }
            repeat(3) { isSyncing.value = !isSyncing.value }
            advanceUntilIdle()

            coVerify(exactly = 1) { getCurrentResidualFatigueUseCase(any(), any()) }
        }

    // Regression: this lookup runs outside GetDashboardDataUseCase's try/catch and does an
    // unbounded workout scan, so a DB failure escaped the combine transform and killed stateIn's
    // sharing coroutine — where the same failure during card building degrades to an errorMessage.
    @Test
    fun `uiState survives a failure while resolving live residual fatigue`() =
        runTest(testDispatcher) {
            val summary = DailySummary(date = LocalDate.of(2026, 7, 29))
            coEvery { getCurrentResidualFatigueUseCase(any(), any()) } throws IOException("db unavailable")
            configureDashboardFlows(
                isSyncing = MutableStateFlow(false),
                recalcProgress = MutableStateFlow(null),
                summary = summary,
            )

            val state = viewModel.uiState.first { it.summary == summary }

            assertEquals(summary, state.summary)
            verify {
                getDashboardDataUseCase.invoke(
                    summary = any(),
                    prefs = any(),
                    date = any(),
                    lastSleepSession = any(),
                    rasSummaries = any(),
                    circadianResult = any(),
                    heartRateSummary = any(),
                    todayStrainIncrease = any(),
                    todayRasIncrease = any(),
                    bodyTempBaseline = any(),
                    liveResidualFatigue = LiveResidualFatigue.Unavailable,
                )
            }
        }
}
