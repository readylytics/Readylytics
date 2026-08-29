package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * WP-27 impulse-provenance locks: a fatigue impulse must always be the freshly calculated
 * selected-model canonical per-workout TRIMP, never a stale persisted `modelTrimp` and never the
 * Edwards `trimp` fallback — plus the day-partitioning invariant the walk-forward accumulator relies
 * on. Shares its DAO/repository fixture with `ResidualFatigueWalkForwardDeterminismTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResidualFatigueCanonicalTrimpTest : ResidualFatigueWalkForwardTestBase() {
    /**
     * The design invariant the removed conflicting-impulse `throw` was guarding:
     * `getWorkoutsInRange` partitions strictly by `startTime`, so a midnight-crossing workout is
     * registered by exactly one day of the walk-forward. It is then consumed at the first evaluation
     * at or after its `endTime` — never at its start day, whose evaluation point precedes it.
     */
    @Test
    fun `midnight-crossing workout is registered once and consumed after its end time`() =
        runTest {
            val day0Start = day0.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val day1Start = day1.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val workout =
                workoutRecord(
                    id = "midnight-crosser",
                    startTime = day0Start + 23 * HOUR_MS,
                    endTime = day1Start + HOUR_MS / 2,
                    trimp = 80f,
                    modelTrimp = null,
                )
            val store = stubProductionWorkoutStore(workout)
            val registeringRanges = mutableListOf<Pair<Long, Long>>()
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } answers {
                val from = firstArg<Long>()
                val to = secondArg<Long>()
                store.workouts.values
                    .filter { it.startTime in from until to }
                    .also { if (it.isNotEmpty()) registeringRanges += from to to }
            }

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    maxHeartRate = 190,
                    autoCalculateMaxHr = false,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val fatigueByDate = runWalkForward(day0, day2, prefs)

            val registeringDays = registeringRanges.distinct()
            assertEquals(1, registeringDays.size, "Workout must be registered by exactly one day")
            assertEquals(day0Start, registeringDays.single().first, "Registered by its start day")
            assertEquals(
                0f,
                requireNotNull(fatigueByDate[day0]),
                EPSILON,
                "Start day evaluation precedes the end time",
            )
            val impulse =
                listOf(
                    ComputeResidualFatigueUseCase.FatigueWorkoutInput(
                        workout.endTime,
                        store.writtenModelTrimps.first(),
                    ),
                )
            listOf(day1, day2).forEach { day ->
                assertEquals(
                    useCase.compute(evalMs(day), impulse, config),
                    requireNotNull(fatigueByDate[day]),
                    EPSILON,
                    "Day $day: single impulse applied exactly once",
                )
            }
        }

    @Test
    fun `walk-forward uses freshly calculated model TRIMP instead of stale persisted impulse`() =
        runTest {
            val day0Start = day0.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val workout =
                workoutRecord(
                    id = "stale-model",
                    startTime = day0Start + HOUR_MS,
                    endTime = day0Start + 2 * HOUR_MS,
                    trimp = 33f,
                    modelTrimp = 135f,
                )
            val pass =
                runProductionPass(
                    workout,
                    UserPreferences(
                        scoringZoneId = zoneId.id,
                        maxHeartRate = 190,
                        autoCalculateMaxHr = false,
                        residualFatigueHalfLifeHours = config.halfLifeHours,
                        residualFatigueGain = config.fatigueGain,
                    ),
                )
            val firstFatigueImpulse = impulseAtWorkoutEnd(pass.firstFatigue, workout.endTime)

            assertEquals(pass.firstCanonicalModelTrimp, firstFatigueImpulse, EPSILON)
            assertEquals(pass.firstFatigue, pass.secondFatigue, EPSILON)
            assertNotEquals(135f, firstFatigueImpulse, EPSILON)
            assertNotEquals(33f, firstFatigueImpulse, EPSILON)
        }

    @Test
    fun `walk-forward never uses Edwards fallback for a missing model TRIMP`() =
        runTest {
            val day0Start = day0.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val models = trimpModelPreferences()

            val canonicalTrimps =
                models.mapIndexed { index, prefs ->
                    val workout =
                        workoutRecord(
                            id = "missing-model-$index",
                            startTime = day0Start + HOUR_MS,
                            endTime = day0Start + 2 * HOUR_MS,
                            trimp = 80f,
                            modelTrimp = null,
                        )
                    val pass = runProductionPass(workout, prefs)
                    val firstFatigueImpulse = impulseAtWorkoutEnd(pass.firstFatigue, workout.endTime)

                    assertEquals(pass.firstCanonicalModelTrimp, firstFatigueImpulse, EPSILON)
                    assertEquals(pass.firstFatigue, pass.secondFatigue, EPSILON)
                    assertNotEquals(80f, firstFatigueImpulse, EPSILON)
                    pass.firstCanonicalModelTrimp
                }

            assertNotEquals(canonicalTrimps[0], canonicalTrimps[1], EPSILON)
            assertNotEquals(canonicalTrimps[0], canonicalTrimps[2], EPSILON)
            assertNotEquals(canonicalTrimps[1], canonicalTrimps[2], EPSILON)
            assertSelectedModelParameters(models, canonicalTrimps)
        }

    private fun trimpModelPreferences(): List<UserPreferences> =
        listOf(
            UserPreferences(
                scoringZoneId = zoneId.id,
                trimpModel = TrimpModel.BANISTER,
                banisterMultiplier = 2f,
                maxHeartRate = 190,
                autoCalculateMaxHr = false,
                residualFatigueHalfLifeHours = config.halfLifeHours,
                residualFatigueGain = config.fatigueGain,
            ),
            UserPreferences(
                scoringZoneId = zoneId.id,
                trimpModel = TrimpModel.CHENG,
                banisterMultiplier = 8f,
                chengBeta = 0.4f,
                zone3MaxBpm = 150,
                maxHeartRate = 190,
                autoCalculateMaxHr = false,
                residualFatigueHalfLifeHours = config.halfLifeHours,
                residualFatigueGain = config.fatigueGain,
            ),
            UserPreferences(
                scoringZoneId = zoneId.id,
                trimpModel = TrimpModel.I_TRIMP,
                banisterMultiplier = 8f,
                itrimB = 3f,
                maxHeartRate = 190,
                autoCalculateMaxHr = false,
                residualFatigueHalfLifeHours = config.halfLifeHours,
                residualFatigueGain = config.fatigueGain,
            ),
        )

    private suspend fun canonicalModelTrimpFor(
        workoutId: String,
        prefs: UserPreferences,
    ): Float {
        val day0Start = day0.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val workout =
            workoutRecord(
                id = workoutId,
                startTime = day0Start + HOUR_MS,
                endTime = day0Start + 2 * HOUR_MS,
                trimp = 80f,
                modelTrimp = null,
            )
        return runProductionPass(workout, prefs).firstCanonicalModelTrimp
    }

    private suspend fun assertSelectedModelParameters(
        models: List<UserPreferences>,
        canonicalTrimps: List<Float>,
    ) {
        assertEquals(
            canonicalTrimps[1],
            canonicalModelTrimpFor("cheng-no-banister", models[1].copy(banisterMultiplier = 1f)),
            EPSILON,
        )
        assertNotEquals(
            canonicalTrimps[1],
            canonicalModelTrimpFor("cheng-different-beta", models[1].copy(chengBeta = 0.1f)),
            EPSILON,
        )
        assertEquals(
            canonicalTrimps[2],
            canonicalModelTrimpFor("itrimp-no-banister", models[2].copy(banisterMultiplier = 1f)),
            EPSILON,
        )
        assertNotEquals(
            canonicalTrimps[2],
            canonicalModelTrimpFor("itrimp-different-b", models[2].copy(itrimB = 1f)),
            EPSILON,
        )
    }
}
