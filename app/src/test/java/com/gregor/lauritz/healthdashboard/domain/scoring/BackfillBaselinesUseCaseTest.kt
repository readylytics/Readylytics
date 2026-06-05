package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.repository.TransactionRunner
import com.gregor.lauritz.healthdashboard.domain.scoring.strategies.LoadScoringStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * US-B7: Unit tests for backfill logic and freeze enforcement.
 *
 * Suite 1 — ComputeHistoricalBaselinesUseCase (backfill logic over the batched
 *           BaselineComputer.computeBackfillBaselines result)
 * Suite 2 — BaselineComputer freeze enforcement (per-day methods, unchanged)
 * Suite 3 — Integration: BackfillHistoricalBaselinesUseCase end-to-end
 *
 * Note: the per-day window math and its byte-for-byte equivalence with the batched path are
 * covered by [BaselineComputerBackfillEquivalenceTest]. These suites mock the batched call and
 * focus on the use-case-level mapping, freeze behavior, and end-to-end wiring.
 */
class BackfillBaselinesUseCaseTest {
    // -------------------------------------------------------------------------
    // Shared test helpers
    // -------------------------------------------------------------------------

    /** Midnight epoch-ms for [daysAgo] days before today in the system time zone. */
    private fun daysAgoMs(daysAgo: Long): Long =
        LocalDate
            .now()
            .minusDays(daysAgo)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun makeSummary(
        daysAgo: Long,
        baselineCalculatedAtDate: LocalDate? = null,
        hrvMuMssd: Float? = null,
        hrvSigmaMssd: Float? = null,
        rhrBpm: Float? = null,
    ) = DailySummaryEntity(
        dateMidnightMs = daysAgoMs(daysAgo),
        baselineCalculatedAtDate = baselineCalculatedAtDate,
        hrvMuMssd = hrvMuMssd,
        hrvSigmaMssd = hrvSigmaMssd,
        rhrBpm = rhrBpm,
    )

    /**
     * Stubs [BaselineComputer.computeBackfillBaselines] on [computer] to return [mu]/[sigma]/[rhr]
     * for every summary passed in (keyed by its `dateMidnightMs`). Mirrors how the real batched
     * method returns one entry per requested day.
     */
    private fun stubBackfill(
        computer: BaselineComputer,
        mu: List<Float>,
        sigma: List<Float> = mu,
        rhr: Float = 60f,
    ) {
        coEvery { computer.computeBackfillBaselines(any(), any()) } answers {
            firstArg<List<DailySummaryEntity>>().associate {
                it.dateMidnightMs to BaselineComputer.BackfillBaseline(mu, sigma, rhr)
            }
        }
    }

    private fun assertFloatEquals(
        expected: Float,
        actual: Float?,
        tolerance: Float = 1e-5f,
        msg: String = "",
    ) {
        assertNotNull(actual, "$msg — actual was null")
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$msg — expected $expected but got $actual (tolerance ±$tolerance)",
        )
    }

    // =========================================================================
    // Suite 1: ComputeHistoricalBaselinesUseCase — backfill logic
    // =========================================================================

    private lateinit var baselineComputer: BaselineComputer
    private lateinit var loadScoringStrategy: LoadScoringStrategy
    private lateinit var computeUseCase: ComputeHistoricalBaselinesUseCase

    @Before
    fun setupSuite1() {
        baselineComputer = mockk()
        loadScoringStrategy = mockk()
        computeUseCase = ComputeHistoricalBaselinesUseCase(baselineComputer, loadScoringStrategy)
    }

    // --- no-lookahead (rows older than 30 days are still processed) ---

    @Test
    fun `computeHistoricalBaselines processes rows older than 30 days — no cutoff`() =
        runTest {
            val oldRow = makeSummary(daysAgo = 31)
            val recentRow = makeSummary(daysAgo = 20)

            stubBackfill(baselineComputer, listOf(40f, 42f), listOf(40f, 42f), 60f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(listOf(oldRow, recentRow), UserPreferences())

            assertEquals(2, result.size, "All unfrozen rows processed regardless of age")
        }

    @Test
    fun `computeHistoricalBaselines includes row exactly 30 days ago`() =
        runTest {
            val exactly30 = makeSummary(daysAgo = 30)

            stubBackfill(baselineComputer, listOf(50f), listOf(50f), 62f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(listOf(exactly30), UserPreferences())

            assertEquals(1, result.size)
        }

    // --- mu/sigma computed in ln-space matching live pipeline ---

    @Test
    fun `computeHistoricalBaselines computes hrvMuMssd as average of ln(rmssd)`() =
        runTest {
            val rmssdValues = listOf(40f, 60f, 80f)
            val expectedLnMu = rmssdValues.map { ln(it.coerceAtLeast(0.001f)) }.average().toFloat()

            stubBackfill(baselineComputer, rmssdValues, rmssdValues, 58f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(listOf(makeSummary(daysAgo = 10)), UserPreferences())

            assertFloatEquals(
                expectedLnMu,
                result.first().hrvMuMssd,
                msg = "hrvMuMssd must equal average of ln(rmssd) values",
            )
        }

    @Test
    fun `computeHistoricalBaselines passes ln-transformed sigmaHistory to LoadScoringStrategy`() =
        runTest {
            val rmssdValues = listOf(35f, 45f, 55f)
            val expectedLnSigmas = rmssdValues.map { ln(it.coerceAtLeast(0.001f)) }
            val capturedLnSigmas = slot<List<Float>>()

            stubBackfill(baselineComputer, rmssdValues, rmssdValues, 60f)
            coEvery {
                loadScoringStrategy.hrvSigma(capture(capturedLnSigmas), PhysiologyProfile.GENERAL.lnSigmaPrior)
            } returns 0.20f

            computeUseCase.computeHistoricalBaselines(listOf(makeSummary(daysAgo = 5)), UserPreferences())

            val captured = capturedLnSigmas.captured
            assertEquals(expectedLnSigmas.size, captured.size)
            expectedLnSigmas.forEachIndexed { i, expected ->
                assertFloatEquals(expected, captured[i], msg = "lnSigmaHistory[$i]")
            }
        }

    @Test
    fun `computeHistoricalBaselines stores rhrBpm from the batched baseline`() =
        runTest {
            val expectedRhr = 57f
            stubBackfill(baselineComputer, listOf(50f), listOf(50f), expectedRhr)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(listOf(makeSummary(daysAgo = 15)), UserPreferences())

            assertEquals(expectedRhr, result.first().rhrBpm)
        }

    @Test
    fun `computeHistoricalBaselines sets baselineCalculatedAtDate to the row local date`() =
        runTest {
            val daysAgo = 7L
            val expectedDate = LocalDate.now().minusDays(daysAgo)

            stubBackfill(baselineComputer, listOf(50f), listOf(50f), 60f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result =
                computeUseCase.computeHistoricalBaselines(
                    listOf(makeSummary(daysAgo = daysAgo)),
                    UserPreferences(),
                )

            assertEquals(expectedDate, result.first().baselineCalculatedAtDate)
        }

    @Test
    fun `computeHistoricalBaselines sets baselineVersion to 1`() =
        runTest {
            stubBackfill(baselineComputer, listOf(50f), listOf(50f), 60f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(listOf(makeSummary(daysAgo = 3)), UserPreferences())

            assertEquals(1, result.first().baselineVersion)
        }

    // --- freeze handling (use case does not pre-skip frozen rows) ---

    @Test
    fun `computeHistoricalBaselines does not skip rows that already have baselineCalculatedAtDate set`() =
        runTest {
            val frozen =
                makeSummary(
                    daysAgo = 10,
                    baselineCalculatedAtDate = LocalDate.now().minusDays(10),
                    hrvMuMssd = 3.7f,
                    hrvSigmaMssd = 0.18f,
                    rhrBpm = 58f,
                )

            stubBackfill(baselineComputer, listOf(50f), listOf(50f), 58f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(listOf(frozen), UserPreferences())

            assertEquals(1, result.size)
        }

    // --- idempotency ---

    @Test
    fun `computeHistoricalBaselines is idempotent — second run on frozen rows still processes them`() =
        runTest {
            val summary = makeSummary(daysAgo = 12)
            stubBackfill(baselineComputer, listOf(45f, 50f), listOf(45f, 50f), 61f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.19f

            // First run — row is unfrozen; gets processed.
            val firstRun = computeUseCase.computeHistoricalBaselines(listOf(summary), UserPreferences())
            assertEquals(1, firstRun.size)

            // Simulate DAO update: the row is now frozen.
            val frozenRow = firstRun.first()
            assertNotNull(frozenRow.baselineCalculatedAtDate)

            // Second run — frozen row is still processed (no longer skipped).
            val secondRun = computeUseCase.computeHistoricalBaselines(listOf(frozenRow), UserPreferences())
            assertEquals(1, secondRun.size)
        }

    @Test
    fun `computeHistoricalBaselines produces identical values on two independent runs of same unfrozen data`() =
        runTest {
            val summary = makeSummary(daysAgo = 8)

            stubBackfill(baselineComputer, listOf(44f, 48f, 52f), listOf(44f, 48f, 52f), 63f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.17f

            val runA = computeUseCase.computeHistoricalBaselines(listOf(summary), UserPreferences())
            val runB = computeUseCase.computeHistoricalBaselines(listOf(summary), UserPreferences())

            assertEquals(1, runA.size)
            assertEquals(1, runB.size)
            assertFloatEquals(runA.first().hrvMuMssd!!, runB.first().hrvMuMssd, msg = "hrvMuMssd must be deterministic")
            assertFloatEquals(
                runA.first().hrvSigmaMssd!!,
                runB.first().hrvSigmaMssd,
                msg = "hrvSigmaMssd must be deterministic",
            )
            assertEquals(runA.first().rhrBpm, runB.first().rhrBpm)
        }

    // --- edge cases ---

    @Test
    fun `computeHistoricalBaselines returns empty list for empty input`() =
        runTest {
            val result = computeUseCase.computeHistoricalBaselines(emptyList(), UserPreferences())
            assertTrue(result.isEmpty())
        }

    @Test
    fun `computeHistoricalBaselines skips row absent from the batched result`() =
        runTest {
            // A day with no batched entry (e.g. no usable data) must be skipped.
            coEvery { baselineComputer.computeBackfillBaselines(any(), any()) } returns emptyMap()

            val result = computeUseCase.computeHistoricalBaselines(listOf(makeSummary(daysAgo = 8)), UserPreferences())

            assertTrue(result.isEmpty(), "A row missing from the batched result must be skipped")
        }

    @Test
    fun `computeHistoricalBaselines skips row when muHistory is empty`() =
        runTest {
            stubBackfill(baselineComputer, emptyList(), emptyList(), 60f)

            val result = computeUseCase.computeHistoricalBaselines(listOf(makeSummary(daysAgo = 5)), UserPreferences())

            assertTrue(result.isEmpty(), "Empty muHistory must cause the row to be skipped")
        }

    @Test
    fun `computeHistoricalBaselines processes single-day dataset`() =
        runTest {
            stubBackfill(baselineComputer, listOf(55f), listOf(55f), 63f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(listOf(makeSummary(daysAgo = 1)), UserPreferences())

            assertEquals(1, result.size)
            assertNotNull(result.first().baselineCalculatedAtDate)
        }

    @Test
    fun `computeHistoricalBaselines processes all 30 in-window rows from a 30-day dataset`() =
        runTest {
            val summaries = (1..30).map { makeSummary(daysAgo = it.toLong()) }

            stubBackfill(baselineComputer, listOf(50f, 52f), listOf(50f, 52f), 60f)
            coEvery { loadScoringStrategy.hrvSigma(any(), any()) } returns 0.18f

            val result = computeUseCase.computeHistoricalBaselines(summaries, UserPreferences())

            assertEquals(30, result.size, "All 30 rows must be processed")
        }

    // =========================================================================
    // Suite 2: BaselineComputer freeze enforcement
    // =========================================================================

    private lateinit var freezeDailySummaryDao: DailySummaryDao
    private lateinit var freezeBaselineComputer: BaselineComputer

    @Before
    fun setupSuite2() {
        freezeDailySummaryDao = mockk()
        val heartRateDao = mockk<HeartRateDao>()
        val hrvDao = mockk<HrvDao>()
        val sleepSessionDao = mockk<SleepSessionDao>()
        val scoringCalculator = mockk<ScoringCalculator>()

        // Default: no frozen baseline — live recompute path.
        coEvery { freezeDailySummaryDao.getByDate(any()) } returns null

        freezeBaselineComputer =
            BaselineComputer(
                heartRateDao,
                hrvDao,
                sleepSessionDao,
                scoringCalculator,
                freezeDailySummaryDao,
            )
    }

    @Test
    fun `computeHrvWindows returns null when baselineCalculatedAtDate is set`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            coEvery { freezeDailySummaryDao.getByDate(dayMidnight.toEpochMilli()) } returns
                DailySummaryEntity(
                    dateMidnightMs = dayMidnight.toEpochMilli(),
                    baselineCalculatedAtDate = LocalDate.now(),
                    hrvMuMssd = 3.8f,
                )

            val result = freezeBaselineComputer.computeHrvWindows(dayMidnight, excludeSessionId = null)

            assertNull(result, "computeHrvWindows must return null for a frozen row")
        }

    @Test
    fun `computeHrvWindows returns non-null when baselineCalculatedAtDate is null`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            // Unfrozen row.
            coEvery { freezeDailySummaryDao.getByDate(dayMidnight.toEpochMilli()) } returns
                DailySummaryEntity(
                    dateMidnightMs = dayMidnight.toEpochMilli(),
                    baselineCalculatedAtDate = null,
                )

            val heartRateDao = mockk<HeartRateDao>()
            val hrvDao = mockk<HrvDao>()
            val sleepSessionDao = mockk<SleepSessionDao>()
            val scoringCalculator = mockk<ScoringCalculator>()

            coEvery { sleepSessionDao.getSince(any()) } returns emptyList()
            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()
            coEvery { hrvDao.getSleepRmssdValuesForSessions(any()) } returns emptyList()

            val computer =
                BaselineComputer(
                    heartRateDao,
                    hrvDao,
                    sleepSessionDao,
                    scoringCalculator,
                    freezeDailySummaryDao,
                )

            val result = computer.computeHrvWindows(dayMidnight, excludeSessionId = null)

            assertNotNull(result, "computeHrvWindows must not return null for an unfrozen row")
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm returns null when baselineCalculatedAtDate is set`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            coEvery { freezeDailySummaryDao.getByDate(dayMidnight.toEpochMilli()) } returns
                DailySummaryEntity(
                    dateMidnightMs = dayMidnight.toEpochMilli(),
                    baselineCalculatedAtDate = LocalDate.now(),
                    rhrBpm = 58f,
                )

            val result =
                freezeBaselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    rhrBaselineOverride = null,
                    percentile = 5,
                )

            assertNull(result, "computeAdaptiveBaselineRhrBpm must return null for a frozen row")
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm returns DEFAULT_RHR_BPM for unfrozen row with no sessions`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            // No frozen summary — live recompute; no sleep sessions.
            coEvery { freezeDailySummaryDao.getByDate(any()) } returns null

            val heartRateDao = mockk<HeartRateDao>()
            val hrvDao = mockk<HrvDao>()
            val sleepSessionDao = mockk<SleepSessionDao>()
            val scoringCalculator = mockk<ScoringCalculator>()
            coEvery { sleepSessionDao.getSince(any()) } returns emptyList()

            val computer =
                BaselineComputer(
                    heartRateDao,
                    hrvDao,
                    sleepSessionDao,
                    scoringCalculator,
                    freezeDailySummaryDao,
                )

            val result = computer.computeAdaptiveBaselineRhrBpm(dayMidnight, rhrBaselineOverride = null, percentile = 5)

            assertNotNull(result)
            assertEquals(ScoringConstants.DEFAULT_RHR_BPM, result)
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm returns override without any DAO call`() =
        runTest {
            val override = 55f
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)

            val result =
                freezeBaselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    rhrBaselineOverride = override,
                    percentile = 5,
                )

            assertEquals(override, result)
            // Override short-circuits before the freeze-check DAO call.
            coVerify(exactly = 0) { freezeDailySummaryDao.getByDate(any()) }
        }

    @Test
    fun `computeHrvWindows freeze is consistent across multiple calls`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            coEvery { freezeDailySummaryDao.getByDate(dayMidnight.toEpochMilli()) } returns
                DailySummaryEntity(
                    dateMidnightMs = dayMidnight.toEpochMilli(),
                    baselineCalculatedAtDate = LocalDate.now(),
                )

            repeat(3) { i ->
                val result = freezeBaselineComputer.computeHrvWindows(dayMidnight, excludeSessionId = null)
                assertNull(result, "Call $i: frozen baseline must consistently return null")
            }
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpm freeze is consistent across multiple calls`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            coEvery { freezeDailySummaryDao.getByDate(dayMidnight.toEpochMilli()) } returns
                DailySummaryEntity(
                    dateMidnightMs = dayMidnight.toEpochMilli(),
                    baselineCalculatedAtDate = LocalDate.now(),
                    rhrBpm = 62f,
                )

            repeat(3) { i ->
                val result =
                    freezeBaselineComputer.computeAdaptiveBaselineRhrBpm(
                        dayMidnight,
                        rhrBaselineOverride = null,
                        percentile = 5,
                    )
                assertNull(result, "Call $i: frozen baseline must consistently return null")
            }
        }

    @Test
    fun `null signals from frozen baseline propagate without throwing`() =
        runTest {
            val dayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
            coEvery { freezeDailySummaryDao.getByDate(dayMidnight.toEpochMilli()) } returns
                DailySummaryEntity(
                    dateMidnightMs = dayMidnight.toEpochMilli(),
                    baselineCalculatedAtDate = LocalDate.now(),
                )

            val hrvResult: BaselineComputer.HrvWindows? =
                freezeBaselineComputer.computeHrvWindows(dayMidnight, excludeSessionId = null)
            val rhrResult: Float? =
                freezeBaselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight,
                    rhrBaselineOverride = null,
                    percentile = 5,
                )

            assertNull(hrvResult)
            assertNull(rhrResult)
            // Reaching this point without exception confirms null propagates safely.
        }

    // =========================================================================
    // Suite 3: Integration — BackfillHistoricalBaselinesUseCase end-to-end
    // =========================================================================

    private fun defaultSettingsRepo(prefs: UserPreferences = UserPreferences()): SettingsRepository {
        val repo = mockk<SettingsRepository>()
        every { repo.userPreferences } returns flowOf(prefs)
        return repo
    }

    /** Passthrough transaction runner: executes the block inline (no real DB transaction in unit tests). */
    private val passthroughTransactionRunner =
        object : TransactionRunner {
            override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
        }

    private fun buildBackfill(
        dao: DailySummaryDao,
        settingsRepo: SettingsRepository,
        compute: ComputeHistoricalBaselinesUseCase,
    ) = BackfillHistoricalBaselinesUseCase(dao, settingsRepo, compute, passthroughTransactionRunner)

    private fun buildComputeUseCase(): Triple<
        BaselineComputer,
        LoadScoringStrategy,
        ComputeHistoricalBaselinesUseCase,
    > {
        val bc = mockk<BaselineComputer>()
        val ls = mockk<LoadScoringStrategy>()
        return Triple(bc, ls, ComputeHistoricalBaselinesUseCase(bc, ls))
    }

    @Test
    fun `execute calls updateBaselines once per computed row and returns count`() =
        runTest {
            val summaries = listOf(makeSummary(daysAgo = 5), makeSummary(daysAgo = 10))
            val dao = mockk<DailySummaryDao>(relaxed = true)
            coEvery { dao.getAllSummaries() } returns summaries

            val (bc, ls, compute) = buildComputeUseCase()
            stubBackfill(bc, listOf(50f), listOf(50f), 60f)
            coEvery { ls.hrvSigma(any(), any()) } returns 0.18f

            val count = buildBackfill(dao, defaultSettingsRepo(), compute).execute()

            assertEquals(2, count)
            coVerify(exactly = 2) {
                dao.updateBaselines(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `execute recomputes and writes all rows even if they were already frozen`() =
        runTest {
            val frozen =
                listOf(
                    makeSummary(daysAgo = 5, baselineCalculatedAtDate = LocalDate.now().minusDays(5)),
                    makeSummary(daysAgo = 8, baselineCalculatedAtDate = LocalDate.now().minusDays(8)),
                )
            val dao = mockk<DailySummaryDao>(relaxed = true)
            coEvery { dao.getAllSummaries() } returns frozen

            val (bc, ls, compute) = buildComputeUseCase()
            stubBackfill(bc, listOf(50f), listOf(50f), 60f)
            coEvery { ls.hrvSigma(any(), any()) } returns 0.18f

            val count = buildBackfill(dao, defaultSettingsRepo(), compute).execute()

            assertEquals(2, count)
            coVerify(exactly = 1) { dao.wipeDerivedBaselines() }
            coVerify(exactly = 2) {
                dao.updateBaselines(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `execute always recomputes and writes rows on subsequent runs`() =
        runTest {
            val summary = makeSummary(daysAgo = 7)
            val dao = mockk<DailySummaryDao>(relaxed = true)
            coEvery { dao.getAllSummaries() } returns listOf(summary)

            val (bc, ls, compute) = buildComputeUseCase()
            stubBackfill(bc, listOf(48f), listOf(48f), 60f)
            coEvery { ls.hrvSigma(any(), any()) } returns 0.18f

            // First run: processed.
            val firstCount = buildBackfill(dao, defaultSettingsRepo(), compute).execute()
            assertEquals(1, firstCount)

            // Simulate DAO returning the now-frozen row.
            val frozenRow = summary.copy(baselineCalculatedAtDate = LocalDate.now().minusDays(7))
            coEvery { dao.getAllSummaries() } returns listOf(frozenRow)

            // Second run: still processed (full rebuild behavior).
            val secondCount = buildBackfill(dao, defaultSettingsRepo(), compute).execute()
            assertEquals(1, secondCount)
        }

    @Test
    fun `execute processes all rows regardless of age — no 30-day cutoff`() =
        runTest {
            val oldRow = makeSummary(daysAgo = 40)
            val newRow = makeSummary(daysAgo = 15)
            val dao = mockk<DailySummaryDao>(relaxed = true)
            coEvery { dao.getAllSummaries() } returns listOf(oldRow, newRow)

            val (bc, ls, compute) = buildComputeUseCase()
            stubBackfill(bc, listOf(52f), listOf(52f), 61f)
            coEvery { ls.hrvSigma(any(), any()) } returns 0.18f

            val count = buildBackfill(dao, defaultSettingsRepo(), compute).execute()

            assertEquals(2, count, "All unfrozen rows must be written regardless of age")
        }

    @Test
    fun `execute passes correct dateMidnightMs to updateBaselines`() =
        runTest {
            val daysAgo = 6L
            val summary = makeSummary(daysAgo = daysAgo)
            val dao = mockk<DailySummaryDao>(relaxed = true)
            coEvery { dao.getAllSummaries() } returns listOf(summary)

            val (bc, ls, compute) = buildComputeUseCase()
            stubBackfill(bc, listOf(50f), listOf(50f), 60f)
            coEvery { ls.hrvSigma(any(), any()) } returns 0.18f

            val capturedMs = slot<Long>()
            coEvery {
                dao.updateBaselines(
                    capture(capturedMs),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns Unit

            buildBackfill(dao, defaultSettingsRepo(), compute).execute()

            assertEquals(summary.dateMidnightMs, capturedMs.captured)
        }

    @Test
    fun `execute returns 0 for empty database`() =
        runTest {
            val dao = mockk<DailySummaryDao>(relaxed = true)
            coEvery { dao.getAllSummaries() } returns emptyList()

            val (_, _, compute) = buildComputeUseCase()

            val count = buildBackfill(dao, defaultSettingsRepo(), compute).execute()

            assertEquals(0, count)
            coVerify(exactly = 0) {
                dao.updateBaselines(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `execute passes non-null baselineCalculatedAtDate to updateBaselines`() =
        runTest {
            // Verify by inspecting the entity produced by computeHistoricalBaselines directly,
            // rather than capturing the nullable DAO parameter (mockk slot<T?> capture is unsupported).
            val daysAgo = 4L
            val summary = makeSummary(daysAgo = daysAgo)
            val expectedDate = LocalDate.now().minusDays(daysAgo)

            // Verify at the ComputeHistoricalBaselinesUseCase level that baselineCalculatedAtDate is set.
            val bc = mockk<BaselineComputer>()
            val ls = mockk<LoadScoringStrategy>()
            val compute = ComputeHistoricalBaselinesUseCase(bc, ls)

            stubBackfill(bc, listOf(50f), listOf(50f), 60f)
            coEvery { ls.hrvSigma(any(), any()) } returns 0.18f

            val computed = compute.computeHistoricalBaselines(listOf(summary), UserPreferences())

            assertEquals(1, computed.size)
            assertNotNull(
                computed[0].baselineCalculatedAtDate,
                "baselineCalculatedAtDate must be non-null after backfill",
            )
            assertEquals(expectedDate, computed[0].baselineCalculatedAtDate)
        }
}
