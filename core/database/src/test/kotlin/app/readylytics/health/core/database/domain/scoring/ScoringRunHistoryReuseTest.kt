package app.readylytics.health.core.database.domain.scoring

import app.readylytics.health.core.model.domain.model.SleepHrSample
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.repository.DailyRasValues
import app.readylytics.health.core.model.domain.repository.FatigueCursor
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardRasWindow
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScoringRunHistoryReuseTest {
    private val useCase = ComputeResidualFatigueUseCase()
    private val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1.0f)

    @Test
    fun `walk-forward baseline context projects each distinct night once across repeated windows`() =
        runTest {
            val totalDays = 120
            val startDate = LocalDate.of(2026, 1, 1)
            val zoneId = ZoneId.of("UTC")
            val projectedSessionIds = mutableListOf<String>()
            val sessions = createTestSessions(totalDays, startDate, zoneId)
            val repository = createMockRepository(sessions) { projectedSessionIds.addAll(it) }

            val sourceGen = 1L
            val snapshotId = "snap-1"
            val baselineContext =
                WalkForwardBaselineContext(
                    sessions = sessions,
                    scoringHistoryRepository = repository,
                    sourceGeneration = sourceGen,
                    scoringSnapshotId = snapshotId,
                )

            // Replay 120 days with a 56-day baseline lookback window
            for (dayIdx in 0 until totalDays) {
                val windowStart = (dayIdx - 56).coerceAtLeast(0)
                val windowSessions = sessions.subList(windowStart, dayIdx + 1)
                val nightValuesMap =
                    baselineContext.nightValuesForSessions(
                        targetSessions = windowSessions,
                        sourceGen = sourceGen,
                        snapshotId = snapshotId,
                    )

                assertEquals(windowSessions.size, nightValuesMap.size)
                for (session in windowSessions) {
                    val nv = nightValuesMap[session.id]
                    assertNotNull(nv)
                    assertEquals(60, nv.averageBpm)
                    assertEquals(51, nv.getPercentileValue(5))
                }
            }

            assertEquals(totalDays, sessions.map { it.id }.distinct().size)
            assertEquals(totalDays, projectedSessionIds.distinct().size)
            assertEquals(totalDays, projectedSessionIds.size)

            val newGen = 2L
            val newValues =
                baselineContext.nightValuesForSessions(
                    targetSessions = sessions.take(5),
                    sourceGen = newGen,
                    snapshotId = snapshotId,
                )
            assertEquals(5, newValues.size)
            assertEquals(totalDays + 5, projectedSessionIds.size)
        }

    @Test
    fun `separate fatigue cursors evaluate morning wake time without same-day workout leakage`() {
        val zoneId = ZoneId.of("Europe/Berlin")
        val day1 = LocalDate.of(2026, 6, 1)
        val wakeTimeDay1 = day1.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
        val workoutDay1Afternoon =
            FatigueWorkoutInput(
                workoutId = "w1",
                endTimeMs = day1.atTime(17, 0).atZone(zoneId).toInstant().toEpochMilli(),
                trimp = 100f,
            )
        val midnightDay1 = day1.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val context =
            WalkForwardFatigueContext(
                seedInputs = emptyList(),
                seedIncomplete = false,
                config = config,
                advancer = createAdvancer(),
            )

        context.registerCanonicalImpulses(listOf(workoutDay1Afternoon))

        val morningCandidateDay1 = context.morningCursor.previewThrough(wakeTimeDay1)
        assertEquals(0f, morningCandidateDay1.fatigue ?: 0f, 1e-6f)
        assertTrue(morningCandidateDay1.consumedInputs.isEmpty())

        val dayEndCandidateDay1 = context.dayEndCursor.previewThrough(midnightDay1)
        val expectedDay1End =
            useCase.compute(
                midnightDay1,
                listOf(ComputeResidualFatigueUseCase.FatigueWorkoutInput(workoutDay1Afternoon.endTimeMs, 100f)),
                config,
            )
        assertEquals(expectedDay1End, dayEndCandidateDay1.fatigue ?: 0f, 1e-6f)
        assertEquals(listOf(workoutDay1Afternoon), dayEndCandidateDay1.consumedInputs)

        context.morningCursor.commit(morningCandidateDay1)
        context.dayEndCursor.commit(dayEndCandidateDay1)
    }

    @Test
    fun `morning cursor includes prior day workouts but excludes subsequent daytime workouts`() {
        val zoneId = ZoneId.of("Europe/Berlin")
        val day1 = LocalDate.of(2026, 6, 1)
        val workoutDay1Afternoon =
            FatigueWorkoutInput(
                workoutId = "w1",
                endTimeMs = day1.atTime(17, 0).atZone(zoneId).toInstant().toEpochMilli(),
                trimp = 100f,
            )
        val midnightDay1 = day1.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val day2 = day1.plusDays(1)
        val wakeTimeDay2 = day2.atTime(7, 30).atZone(zoneId).toInstant().toEpochMilli()
        val workoutDay2Noon =
            FatigueWorkoutInput(
                workoutId = "w2",
                endTimeMs = day2.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli(),
                trimp = 150f,
            )
        val midnightDay2 = day2.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val context =
            WalkForwardFatigueContext(
                seedInputs = emptyList(),
                seedIncomplete = false,
                config = config,
                advancer = createAdvancer(),
            )
        context.registerCanonicalImpulses(listOf(workoutDay1Afternoon))
        context.dayEndCursor.commit(context.dayEndCursor.previewThrough(midnightDay1))

        context.registerCanonicalImpulses(listOf(workoutDay2Noon))

        val morningCandidateDay2 = context.morningCursor.previewThrough(wakeTimeDay2)
        val expectedDay2Morning =
            useCase.compute(
                wakeTimeDay2,
                listOf(ComputeResidualFatigueUseCase.FatigueWorkoutInput(workoutDay1Afternoon.endTimeMs, 100f)),
                config,
            )
        assertEquals(expectedDay2Morning, morningCandidateDay2.fatigue ?: 0f, 1e-6f)
        assertEquals(listOf(workoutDay1Afternoon), morningCandidateDay2.consumedInputs)

        val dayEndCandidateDay2 = context.dayEndCursor.previewThrough(midnightDay2)
        val expectedDay2End =
            useCase.compute(
                midnightDay2,
                listOf(
                    ComputeResidualFatigueUseCase.FatigueWorkoutInput(workoutDay1Afternoon.endTimeMs, 100f),
                    ComputeResidualFatigueUseCase.FatigueWorkoutInput(workoutDay2Noon.endTimeMs, 150f),
                ),
                config,
            )
        assertEquals(expectedDay2End, dayEndCandidateDay2.fatigue ?: 0f, 1e-6f)

        val uncommittedPreview = context.dayEndCursor.previewThrough(midnightDay2 + 3600000L)
        assertNotEquals(context.dayEndCursor.lastEvaluationTimeMs, uncommittedPreview.advancedEvalMs)
        assertEquals(midnightDay1, context.dayEndCursor.lastEvaluationTimeMs)
    }

    @Test
    fun `fatigue cursor correctly handles DST transition and non-monotonic wake times`() {
        // Berlin DST spring forward: 2026-03-29
        val zoneId = ZoneId.of("Europe/Berlin")
        val dstDate = LocalDate.of(2026, 3, 29)
        val wakeTime = dstDate.atTime(8, 0).atZone(zoneId).toInstant().toEpochMilli()
        val workout =
            FatigueWorkoutInput(
                workoutId = "w-dst",
                endTimeMs = dstDate.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli(),
                trimp = 80f,
            )

        val advancer: (Double, Long, Long, List<FatigueWorkoutInput>) -> Pair<Double, Long> = { acc, last, curr, imp ->
            useCase.advanceAccumulator(
                accumulatedFatigue = acc,
                lastEvalMs = last,
                currentEvalMs = curr,
                newImpulses = imp.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
                config = config,
            )
        }

        val cursor =
            FatigueCursor(
                seedInputs = listOf(workout),
                seedIncomplete = false,
                config = config,
                advancer = advancer,
            )

        val candidate = cursor.previewThrough(wakeTime)
        val expected =
            useCase.compute(
                wakeTime,
                listOf(ComputeResidualFatigueUseCase.FatigueWorkoutInput(workout.endTimeMs, 80f)),
                config,
            )
        assertEquals(expected, candidate.fatigue ?: 0f, 1e-6f)
        cursor.commit(candidate)

        // Non-monotonic wake time: earlier than last evaluation
        val earlierWake = wakeTime - 3600000L
        assertTrue(earlierWake <= cursor.lastEvaluationTimeMs)
    }

    @Test
    fun `walk-forward ras window matches reference rolling sum across sparse and null days`() {
        val startDate = LocalDate.of(2026, 1, 10)
        // 6 seed days: 2026-01-04 to 2026-01-09
        val seedDays =
            (1..6).map { i ->
                val date = startDate.minusDays(i.toLong())
                DailyRasValues(
                    date = date,
                    rasWorkoutOnly = if (i % 2 == 0) null else i * 10f,
                    rasEverydayHr = i * 15f,
                )
            }.reversed()

        val rasWindow = WalkForwardRasWindow(seedDays)

        // Reference 6-day sum for startDate
        val refSum = seedDays.mapNotNull { it.rasWorkoutOnly }.sum()
        assertEquals(refSum, rasWindow.sumWorkoutOnlyBefore(startDate), 1e-6f)

        // Advance 10 days, with sparse days
        var currentDate = startDate
        val history = seedDays.toMutableList()

        for (step in 1..10) {
            val dailyVal =
                if (step % 3 == 0) {
                    null
                } else {
                    step * 12f
                }
            val committed =
                DailyRasValues(
                    date = currentDate,
                    rasWorkoutOnly = dailyVal,
                    rasEverydayHr = step * 18f,
                )

            // Calculate sum before currentDate
            val validDates = (1..6).map { currentDate.minusDays(it.toLong()) }.toSet()
            val expectedSixDaySum = history.filter { it.date in validDates }.mapNotNull { it.rasWorkoutOnly }.sum()

            assertEquals(expectedSixDaySum, rasWindow.sumWorkoutOnlyBefore(currentDate), 1e-6f)

            // Commit day
            history.add(committed)
            rasWindow.commit(committed)
            currentDate = currentDate.plusDays(1)
        }
    }

    private fun createTestSessions(count: Int, startDate: LocalDate, zoneId: ZoneId): List<SleepSession> =
        (0 until count).map { dayOffset ->
            val date = startDate.plusDays(dayOffset.toLong())
            val startMs = date.atTime(22, 0).atZone(zoneId).toInstant().toEpochMilli()
            val endMs = date.plusDays(1).atTime(6, 0).atZone(zoneId).toInstant().toEpochMilli()
            SleepSession(
                id = "session-$dayOffset",
                startTime = startMs,
                endTime = endMs,
                durationMinutes = 480,
                efficiency = 0.9f,
                deepSleepMinutes = 90,
                remSleepMinutes = 90,
                lightSleepMinutes = 270,
                awakeMinutes = 30,
            )
        }

    private fun createMockRepository(
        sessions: List<SleepSession>,
        onProjectSessionIds: (List<String>) -> Unit,
    ): ScoringHistoryRepository {
        val repository = mockk<ScoringHistoryRepository>()
        val samplesBySession =
            sessions.associate { session ->
                val bpmList = (50..70).toList()
                session.id to bpmList.map { SleepHrSample(session.id, it) }
            }
        coEvery { repository.getSleepHrProjectionForSessions(any()) } answers {
            val ids = firstArg<List<String>>()
            onProjectSessionIds(ids)
            ids.flatMap { samplesBySession[it].orEmpty() }
        }
        coEvery { repository.getAvgSleepHrForSessions(any()) } answers {
            firstArg<List<String>>().associateWith { 60 }
        }
        coEvery { repository.getSleepRmssdForSessionsMap(any()) } answers {
            firstArg<List<String>>().associateWith { listOf(50f) }
        }
        return repository
    }

    private fun createAdvancer(): (Double, Long, Long, List<FatigueWorkoutInput>) -> Pair<Double, Long> =
        { acc, last, curr, imp ->
            useCase.advanceAccumulator(
                accumulatedFatigue = acc,
                lastEvalMs = last,
                currentEvalMs = curr,
                newImpulses = imp.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
                config = config,
            )
        }
}
