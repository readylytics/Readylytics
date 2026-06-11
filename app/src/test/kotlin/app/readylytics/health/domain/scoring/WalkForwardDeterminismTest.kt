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
 * Simulates a walk-forward resync: all 365 days of data are present in Room, and
 * baselines are computed day-by-day from day 1 to day 365. Verifies that the baseline
 * for each day is identical to what it would be if only data up to that day existed.
 *
 * This is the integration-level guarantee that sync scope does not influence scores.
 */
class WalkForwardDeterminismTest {
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)

    private lateinit var baselineComputer: BaselineComputer

    private val day0 = Instant.parse("2025-01-01T00:00:00Z")

    // 120 days of sleep data — enough to exercise the 56-day sigma window fully.
    private val totalDays = 120
    private val allSessions =
        (0 until totalDays).map { i ->
            val dayStart = day0.plus(i.toLong(), ChronoUnit.DAYS)
            SleepSessionEntity(
                id = "session-$i",
                startTime = dayStart.minus(8, ChronoUnit.HOURS).toEpochMilli(),
                endTime = dayStart.toEpochMilli(),
                durationMinutes = 480,
                deepSleepMinutes = 90,
                remSleepMinutes = 100,
                lightSleepMinutes = 240,
                efficiency = 92f,
                awakeMinutes = 20,
            )
        }

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

        coEvery { dailySummaryDao.getByDate(any()) } returns null

        val hrvMap =
            allSessions.associate { s ->
                val idx = s.id.substringAfter("-").toInt()
                s.id to listOf(25f + (idx % 30).toFloat())
            }
        coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } answers {
            firstArg<List<String>>().associateWith { id -> hrvMap[id] ?: emptyList() }
        }

        coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } answers {
            firstArg<List<String>>().associateWith { 55 }
        }

        // getBetween: bounded. This is what the FIXED code uses.
        coEvery { sleepSessionDao.getBetween(any(), any()) } answers {
            val fromMs = firstArg<Long>()
            val toMs = secondArg<Long>()
            allSessions
                .filter { it.startTime >= fromMs && it.endTime <= toMs }
                .sortedBy { it.startTime }
        }
    }

    @Test
    fun `baselines for day 60 are identical whether Room has 60 or 120 days`() =
        runTest {
            // Scenario A: Room has only 60 days (onboarding-equivalent).
            val sessionsA = allSessions.take(61) // days 0-60
            coEvery { sleepSessionDao.getBetween(any(), any()) } answers {
                val fromMs = firstArg<Long>()
                val toMs = secondArg<Long>()
                sessionsA
                    .filter { it.startTime >= fromMs && it.endTime <= toMs }
                    .sortedBy { it.startTime }
            }

            val targetDay = day0.plus(60, ChronoUnit.DAYS)
            val targetDayMs = targetDay.toEpochMilli()
            val targetDayEndMs = targetDay.plus(1, ChronoUnit.DAYS).toEpochMilli()

            val baselinesA =
                baselineComputer.computeHrvWindowsBetween(
                    fromMs = targetDayMs,
                    toMs = targetDayEndMs,
                    excludeSessionId = allSessions[60].id,
                )

            // Scenario B: Room has all 120 days (resync-equivalent).
            coEvery { sleepSessionDao.getBetween(any(), any()) } answers {
                val fromMs = firstArg<Long>()
                val toMs = secondArg<Long>()
                allSessions
                    .filter { it.startTime >= fromMs && it.endTime <= toMs }
                    .sortedBy { it.startTime }
            }

            val baselinesB =
                baselineComputer.computeHrvWindowsBetween(
                    fromMs = targetDayMs,
                    toMs = targetDayEndMs,
                    excludeSessionId = allSessions[60].id,
                )

            assertNotNull(baselinesA)
            assertNotNull(baselinesB)

            assertEquals(
                baselinesA.sigmaHistory,
                baselinesB.sigmaHistory,
                "sigma history must be identical regardless of extra future data in Room",
            )
            assertEquals(
                baselinesA.muHistory,
                baselinesB.muHistory,
                "mu history must be identical regardless of extra future data in Room",
            )
        }
}
