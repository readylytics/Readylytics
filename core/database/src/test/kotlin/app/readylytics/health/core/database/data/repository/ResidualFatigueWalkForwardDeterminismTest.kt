package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WP-27 shadow-mode determinism locks for the residual-fatigue walk-forward.
 *
 * Verifies the §9 guarantees that matter while fatigue stays shadow-only:
 *  - the accumulator (walk-forward) reproduces the summation formula exactly,
 *  - partial vs full sync ranges produce identical fatigue for overlapping days,
 *  - the single-day fallback matches the walk-forward value for the same day.
 *
 * Readiness is untouched by construction: the engine's Readiness path never reads
 * residualFatigue; these tests only assert fatigue is populated. The canonical-TRIMP
 * provenance scenarios live in `ResidualFatigueCanonicalTrimpTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResidualFatigueWalkForwardDeterminismTest : ResidualFatigueWalkForwardTestBase() {
    @Test
    fun `walk-forward accumulator reproduces summation formula per day`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val fatigueByDate = runWalkForward(day0, day2, prefs)

            assertEquals(3, fatigueByDate.size)
            listOf(day0, day1, day2).forEach { day ->
                assertEquals(
                    expectedFatigue(day, workouts),
                    fatigueByDate[day],
                    "Day $day: accumulator must equal the summation formula",
                )
            }
        }

    @Test
    fun `partial walk-forward equals full walk-forward for overlapping days`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val full = runWalkForward(day0, day2, prefs)
            val partial = runWalkForward(day1, day2, prefs)

            listOf(day1, day2).forEach { day ->
                assertEquals(
                    full[day],
                    partial[day],
                    "Day $day: residualFatigue must not depend on sync range",
                )
            }
        }

    @Test
    fun `single-day fallback matches walk-forward value for the same day`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val walkForward = runWalkForward(day0, day1, prefs)

            val singleDay = repo.computeDailySummary(day1)

            assertEquals(walkForward[day1], singleDay.residualFatigue)
        }

    @Test
    fun `single-day fallback matches walk-forward for workout in the seed band`() =
        runTest {
            // A workout 10 days back sits in the 8-32-half-life band at the default 24h half-life:
            // inside the walk-forward's 32-day seed window but outside a naive 8x-half-life fallback
            // window. Both paths must cover the same window, or the same day scores differently
            // depending on which path recomputes it (spec §9 determinism).
            val oldWorkout =
                FatigueWorkoutInput(
                    workoutId = "old-workout",
                    endTimeMs =
                        day0
                            .minusDays(10)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli() + 2 * HOUR_MS,
                    trimp = 30f,
                )
            stubFatigueWorkouts(listOf(oldWorkout))

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val walkForward = runWalkForward(day0, day0, prefs)

            val singleDay = repo.computeDailySummary(day0)

            assertEquals(walkForward[day0], singleDay.residualFatigue)
        }

    @Test
    fun `walk-forward retains a seed workout that straddles the lower lookback bound`() =
        runTest {
            val seedFromMs =
                day0
                    .minusDays(32)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val straddlingWorkout =
                FatigueWorkoutInput(
                    workoutId = "lower-bound-straddling",
                    endTimeMs = seedFromMs + HOUR_MS,
                    trimp = 40f,
                )
            stubFatigueWorkouts(
                workouts = listOf(straddlingWorkout),
                startTimeMs = { it.endTimeMs - 2 * HOUR_MS },
            )

            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val walkForward = runWalkForward(day0, day0, prefs)
            val singleDay = repo.computeDailySummary(day0)

            assertEquals(expectedFatigue(day0, listOf(straddlingWorkout)), walkForward[day0])
            assertEquals(singleDay.residualFatigue, walkForward[day0])
        }

    @Test
    fun `disabled fatigue persists null on both walk-forward and single-day paths`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val disabledPrefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    residualFatigueEnabled = false,
                    residualFatigueHalfLifeHours = config.halfLifeHours,
                    residualFatigueGain = config.fatigueGain,
                )
            val walkForwardByDate = runWalkForward(day0, day1, disabledPrefs)
            assertNull(walkForwardByDate[day0], "Walk-forward with fatigue disabled must persist null")

            every { settingsRepo.userPreferences } returns flowOf(disabledPrefs)
            val singleDay = repo.computeDailySummary(day1)
            assertNull(singleDay.residualFatigue, "Single-day with fatigue disabled must persist null")
        }

    @Test
    fun `fetchWalkForwardFatigueContext holds sorted end-time impulse series`() =
        runTest {
            val workouts = workoutInputs()
            stubFatigueWorkouts(workouts)

            val context = repo.fetchWalkForwardFatigueContext(day0, day2, zoneId)

            assertEquals(
                workouts.map { it.endTimeMs },
                context.takeImpulsesThrough(Long.MAX_VALUE).map { it.endTimeMs },
                "Prefetch must preserve ascending end-time order",
            )
            assertEquals(0.0, context.accumulatedFatigue)
            assertEquals(Long.MIN_VALUE, context.lastEvaluationTimeMs)
        }
}
