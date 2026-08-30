package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResidualFatigueExactReconstructionTest {
    private val dataLoader = mockk<ScoringDayDataLoader>()
    private val useCase = ComputeResidualFatigueUseCase()
    private val computer = ResidualFatigueComputer(dataLoader, useCase)
    private val zoneId = ZoneId.of("UTC")
    private val evaluationDay = LocalDate.of(2026, 1, 1)
    private val config = ResidualFatigueConfig(enabled = true, halfLifeHours = 96f, fatigueGain = 1f)
    private val prefs =
        UserPreferences(
            scoringZoneId = zoneId.id,
            residualFatigueHalfLifeHours = config.halfLifeHours,
            residualFatigueGain = config.fatigueGain,
        )

    @Test
    fun `retained long-tail workouts produce exact fatigue for full partial and single-day walks`() =
        runTest {
            val workouts = longTailWorkouts()
            val startTimes = workouts.associate { it.workoutId to it.endTimeMs - HOUR_MS }
            coEvery { dataLoader.loadCanonicalFatigueSeed(any()) } answers {
                val boundaryMs = firstArg<Long>()
                workouts.filter { startTimes.getValue(it.workoutId) < boundaryMs }
            }
            coEvery { dataLoader.loadCanonicalFatigueInputsThrough(any()) } answers {
                val evaluationTimeMs = firstArg<Long>()
                workouts.filter { it.endTimeMs <= evaluationTimeMs }
            }
            coEvery { dataLoader.loadUnbackfilledCountBefore(any(), any()) } returns 0
            coEvery { dataLoader.loadUnbackfilledCountThrough(any(), any()) } returns 0

            val full = runWalk(evaluationDay.minusDays(120), evaluationDay, workouts, startTimes)
            val partial = runWalk(evaluationDay, evaluationDay, workouts, startTimes)
            val singleDay = requireNotNull(computer.compute(scoringContext(evaluationDay), null))
            val expected = expectedFatigue(workouts)
            val approximation =
                expectedFatigue(
                    workouts.filter { workout ->
                        workout.endTimeMs >=
                            evaluationDay.minusDays(32).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    },
                )

            assertEquals(expected, full, EPSILON, "Full walk must include all retained workouts")
            assertEquals(expected, partial, EPSILON, "Partial walk must reconstruct all retained workouts")
            assertEquals(expected, singleDay, EPSILON, "Single-day fallback must use all retained workouts")
            assertNotEquals(approximation, expected, EPSILON, "Expected value must not be the 32-day approximation")
        }

    @Test
    fun `tied workout ends produce stable id order and identical fatigue across insertion orders`() {
        val tiedEndMs = evaluationDay.atStartOfDay(zoneId).toInstant().toEpochMilli() + 2 * HOUR_MS
        val workoutA = FatigueWorkoutInput("workout-a", tiedEndMs, 30f)
        val workoutZ = FatigueWorkoutInput("workout-z", tiedEndMs, 50f)
        val firstInputs = WalkForwardFatigueContext(listOf(workoutZ, workoutA)).takeImpulsesThrough(evalMs())
        val secondInputs = WalkForwardFatigueContext(listOf(workoutA, workoutZ)).takeImpulsesThrough(evalMs())

        assertEquals(listOf("workout-a", "workout-z"), firstInputs.map { it.workoutId })
        assertEquals(firstInputs, secondInputs)
        assertEquals(expectedFatigue(firstInputs), expectedFatigue(secondInputs), EPSILON)
    }

    @Test
    fun `fetchWalkForwardContext flags an incomplete seed when unbackfilled workouts exist before the boundary`() =
        runTest {
            coEvery { dataLoader.loadCanonicalFatigueSeed(any()) } returns emptyList()
            coEvery { dataLoader.loadUnbackfilledCountBefore(any(), any()) } returns 1

            val context = computer.fetchWalkForwardContext(evaluationDay, zoneId, prefs)

            assertTrue(context.seedIncomplete, "A dropped never-backfilled row must flag the seed incomplete")
        }

    @Test
    fun `compute returns null on the walk-forward path when the seed is incomplete`() =
        runTest {
            coEvery { dataLoader.loadCanonicalFatigueSeed(any()) } returns emptyList()
            coEvery { dataLoader.loadUnbackfilledCountBefore(any(), any()) } returns 1
            val fatigueContext = computer.fetchWalkForwardContext(evaluationDay, zoneId, prefs)

            val result = computer.compute(scoringContext(evaluationDay), fatigueContext)

            assertNull(result, "An incomplete seed must persist null (unknown), not a low value")
        }

    @Test
    fun `never-backfilled gate is bounded by the retention start so it can converge`() =
        runTest {
            val retentionPrefs = prefs.copy(retentionDaysEnabled = true, retentionDays = 365)
            val expectedRetentionStartMs =
                RetentionBounds.resolveHistoricalWindow(retentionPrefs).startTimeMs
            val gateLowerBound = slot<Long>()
            coEvery { dataLoader.loadCanonicalFatigueSeed(any()) } returns emptyList()
            coEvery { dataLoader.loadUnbackfilledCountBefore(capture(gateLowerBound), any()) } returns 0

            computer.fetchWalkForwardContext(evaluationDay, zoneId, retentionPrefs)

            // The gate must not reach past the rows WorkoutTrimpBackfillStatus can repair; an
            // unbounded gate would let one ancient null-modelTrimp row pin the metric to null.
            assertEquals(expectedRetentionStartMs, gateLowerBound.captured)
        }

    @Test
    fun `single-day fallback gate is bounded by the same retention start`() =
        runTest {
            val retentionPrefs = prefs.copy(retentionDaysEnabled = true, retentionDays = 365)
            val expectedRetentionStartMs =
                RetentionBounds.resolveHistoricalWindow(retentionPrefs).startTimeMs
            val gateLowerBound = slot<Long>()
            coEvery { dataLoader.loadUnbackfilledCountThrough(capture(gateLowerBound), any()) } returns 0
            coEvery { dataLoader.loadCanonicalFatigueInputsThrough(any()) } returns emptyList()

            computer.compute(scoringContext(evaluationDay, retentionPrefs), null)

            assertEquals(expectedRetentionStartMs, gateLowerBound.captured)
        }

    @Test
    fun `single-day fallback returns null for unbackfilled workouts`() =
        runTest {
            coEvery { dataLoader.loadUnbackfilledCountThrough(any(), any()) } returns 1

            val result = computer.compute(scoringContext(evaluationDay), null)

            assertNull(result, "The single-day fallback must also report unknown, not a low value")
        }

    private suspend fun runWalk(
        startDate: LocalDate,
        endDate: LocalDate,
        workouts: List<FatigueWorkoutInput>,
        startTimes: Map<String, Long>,
    ): Float {
        val fatigueContext = computer.fetchWalkForwardContext(startDate, zoneId, prefs)
        var day = startDate
        var fatigue = 0f
        while (!day.isAfter(endDate)) {
            val dayStartMs = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val nextDayStartMs = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            fatigueContext.registerCanonicalImpulses(
                workouts.filter { startTimes.getValue(it.workoutId) in dayStartMs until nextDayStartMs },
            )
            fatigue = requireNotNull(computer.compute(scoringContext(day), fatigueContext))
            day = day.plusDays(1)
        }
        return fatigue
    }

    private fun scoringContext(
        day: LocalDate,
        preferences: UserPreferences = this@ResidualFatigueExactReconstructionTest.prefs,
    ): ScoringDayContext =
        mockk {
            every { prefs } returns preferences
            every { nextDayMidnightMs } returns
                day.plusDays(1)
                    .atStartOfDay(this@ResidualFatigueExactReconstructionTest.zoneId)
                    .toInstant()
                    .toEpochMilli()
        }

    private fun longTailWorkouts(): List<FatigueWorkoutInput> =
        listOf(33L, 60L, 120L).map { daysBeforeEvaluation ->
            FatigueWorkoutInput(
                workoutId = "workout-$daysBeforeEvaluation-days-back",
                endTimeMs =
                    evaluationDay
                        .minusDays(daysBeforeEvaluation)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli() + 2 * HOUR_MS,
                trimp = 30f,
            )
        }

    private fun expectedFatigue(workouts: List<FatigueWorkoutInput>): Float =
        useCase.compute(
            evalMs(),
            workouts.map { ComputeResidualFatigueUseCase.FatigueWorkoutInput(it.endTimeMs, it.trimp) },
            config,
        )

    private fun evalMs(): Long = evaluationDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val EPSILON = 0.001f
    }
}
