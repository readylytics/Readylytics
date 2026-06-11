package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.entity.SleepSessionEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Proves that baseline computation for a historical day is identical regardless of how much
 * future data exists in Room at computation time.
 *
 * Regression guard for the getSince/getBetween divergence:
 * - computeHrvWindows uses getSince (unbounded upper end) → leaks future sessions
 * - computeHrvWindowsBetween uses getBetween (bounded) → point-in-time correct
 *
 * During a walk-forward resync, all data is ingested first, then days are recomputed
 * chronologically. If any baseline method uses getSince, it will see sessions from
 * days not yet "reached" in the walk-forward — producing different baselines than
 * a fresh onboarding sync where only those days exist.
 */
class SyncScopeDeterminismTest {
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)

    private lateinit var baselineComputer: BaselineComputer

    // Day 60 midnight — the target day under test.
    private val day0 = Instant.parse("2025-01-01T00:00:00Z")
    private val targetDay = day0.plus(60, ChronoUnit.DAYS) // Feb 2
    private val targetDayMs = targetDay.toEpochMilli()
    private val targetDayEndMs = targetDay.plus(1, ChronoUnit.DAYS).toEpochMilli()

    // Generate 365 days of synthetic sleep sessions (1 per night).
    private val allSessions =
        (0 until 365).map { i ->
            val dayStart = day0.plus(i.toLong(), ChronoUnit.DAYS)
            SleepSessionEntity(
                id = "session-$i",
                startTime = dayStart.minus(8, ChronoUnit.HOURS).toEpochMilli(), // previous evening
                endTime = dayStart.plus(0, ChronoUnit.HOURS).toEpochMilli(), // midnight = day start
                durationMinutes = 480,
                deepSleepMinutes = 90,
                remSleepMinutes = 100,
                lightSleepMinutes = 240,
                awakeMinutes = 50,
                efficiency = 92f,
            )
        }

    // The session ending on the target day.
    private val targetSession = allSessions[60]

    @Before
    fun setup() {
        baselineComputer =
            BaselineComputer(
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                sleepSessionDao = sleepSessionDao,
                scoringCalculator = scoringCalculator,
                dailySummaryDao = dailySummaryDao,
            )

        // Every night is valid for baseline contribution.
        coEvery {
            scoringCalculator.validateNight(any(), any(), any(), any(), any(), any())
        } returns
            ScoringCalculator.NightValidationResult(
                rmssdValid = true,
                rhrValid = true,
                durationValid = true,
                stagesValid = true,
                stagesSuspicious = false,
                hrCoverageValid = true,
            )

        // No frozen baselines.
        coEvery { dailySummaryDao.getByDate(any()) } returns null

        // HRV data: each session has a distinct nightly RMSSD mean.
        val hrvMap =
            allSessions.associate { s ->
                s.id to listOf(30f + (s.id.substringAfter("-").toInt() % 20).toFloat())
            }
        coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } answers {
            val requestedIds = firstArg<List<String>>()
            requestedIds.associateWith { id -> hrvMap[id] ?: emptyList() }
        }

        // Avg HR for validation — stable across sessions.
        coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } answers {
            val requestedIds = firstArg<List<String>>()
            requestedIds.associateWith { 55 }
        }

        // getSince: returns ALL sessions from the given time onward (unbounded upper end).
        coEvery { sleepSessionDao.getSince(any()) } answers {
            val fromMs = firstArg<Long>()
            allSessions.filter { it.startTime >= fromMs }.sortedBy { it.startTime }
        }

        // getBetween: returns only sessions within [fromMs, toMs] (bounded).
        coEvery { sleepSessionDao.getBetween(any(), any()) } answers {
            val fromMs = firstArg<Long>()
            val toMs = secondArg<Long>()
            allSessions
                .filter { it.startTime >= fromMs && it.endTime <= toMs }
                .sortedBy { it.startTime }
        }
    }

    @Test
    fun `HRV sigma history must be identical between bounded and unbounded queries`() =
        runTest {
            // Bounded (correct): computeHrvWindowsBetween — only sees data up to target day.
            val bounded =
                baselineComputer.computeHrvWindowsBetween(
                    fromMs = targetDayMs,
                    toMs = targetDayEndMs,
                    excludeSessionId = targetSession.id,
                )

            // Unbounded (buggy): computeHrvWindows — uses getSince, sees all future data.
            val unbounded =
                baselineComputer.computeHrvWindows(
                    dayMidnight = targetDay,
                    excludeSessionId = targetSession.id,
                )

            assertNotNull(bounded, "bounded HRV windows should not be null")
            assertNotNull(unbounded, "unbounded HRV windows should not be null")

            // THIS ASSERTION SHOULD FAIL BEFORE THE FIX:
            // sigmaHistory from unbounded will have MORE entries (future sessions leaked in).
            assertEquals(
                bounded.sigmaHistory.size,
                unbounded.sigmaHistory.size,
                "sigmaHistory size must match: bounded saw ${bounded.sigmaHistory.size} nights, " +
                    "unbounded saw ${unbounded.sigmaHistory.size} nights. " +
                    "The difference of ${unbounded.sigmaHistory.size - bounded.sigmaHistory.size} " +
                    "entries is future data leaking into the baseline.",
            )

            assertEquals(
                bounded.sigmaHistory,
                unbounded.sigmaHistory,
                "sigmaHistory values must be identical — future data must not leak",
            )

            assertEquals(
                bounded.muHistory,
                unbounded.muHistory,
                "muHistory (last 7 of sigma) must be identical",
            )
        }
}
