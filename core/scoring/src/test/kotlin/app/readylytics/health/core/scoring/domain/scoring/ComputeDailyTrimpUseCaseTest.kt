package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase

import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ComputeDailyTrimpUseCaseTest {
    private val computeWorkoutTrimpUseCase = mockk<ComputeWorkoutTrimpUseCase>()
    private lateinit var useCase: ComputeDailyTrimpUseCase

    @Before
    fun setup() {
        useCase = ComputeDailyTrimpUseCase(computeWorkoutTrimpUseCase)
    }

    @Test
    fun `empty workouts produces zero trimp and no updates`() {
        val result =
            useCase.execute(
                workouts = emptyList(),
                prefs = UserPreferences(),
                rhrBaselineValue = 60f,
                frozenHrMax = null,
            )
        assertEquals(0f, result.totalDailyTrimpRaw, 0.0f)
        assertEquals(0, result.workoutModelTrimpUpdates.size)
        assertEquals(0, result.canonicalWorkoutTrimps.size)
    }

    @Test
    fun `computes daily trimp and identifies workouts needing modelTrimp update`() {
        val workout1 =
            ComputeDailyTrimpUseCase.WorkoutInput(
                id = "w1",
                startTime = 1000L,
                endTime = 2000L,
                currentModelTrimp = null,
                samples =
                    listOf(
                        ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(1200L), 140),
                    ),
            )
        val workout2 =
            ComputeDailyTrimpUseCase.WorkoutInput(
                id = "w2",
                startTime = 3000L,
                endTime = 4000L,
                currentModelTrimp = 35f, // already matches
                samples = emptyList(),
            )

        every {
            computeWorkoutTrimpUseCase.execute(
                workoutStartTime = 1000L,
                workoutEndTime = 2000L,
                workoutAvgHr = 140f,
                samples = any(),
                prefs = any(),
                restingHrBaseline = 60f,
                frozenHrMax = null,
            )
        } returns Result.success(25f)

        every {
            computeWorkoutTrimpUseCase.execute(
                workoutStartTime = 3000L,
                workoutEndTime = 4000L,
                workoutAvgHr = 0f,
                samples = any(),
                prefs = any(),
                restingHrBaseline = 60f,
                frozenHrMax = null,
            )
        } returns Result.success(35f)

        val result =
            useCase.execute(
                workouts = listOf(workout1, workout2),
                prefs = UserPreferences(),
                rhrBaselineValue = 60f,
                frozenHrMax = null,
            )

        assertEquals(60f, result.totalDailyTrimpRaw, 0.001f)
        assertEquals(1, result.workoutModelTrimpUpdates.size)
        assertEquals("w1", result.workoutModelTrimpUpdates[0].workoutId)
        assertEquals(25f, result.workoutModelTrimpUpdates[0].modelTrimp, 0.001f)
        assertCanonicalWorkoutTrimps(result)
    }

    private fun assertCanonicalWorkoutTrimps(result: ComputeDailyTrimpUseCase.DailyTrimpResult) {
        assertEquals(2, result.canonicalWorkoutTrimps.size)
        assertEquals("w1", result.canonicalWorkoutTrimps[0].workoutId)
        assertEquals(2000L, result.canonicalWorkoutTrimps[0].endTimeMs)
        assertEquals(25f, result.canonicalWorkoutTrimps[0].trimp, 0.001f)
        assertEquals("w2", result.canonicalWorkoutTrimps[1].workoutId)
        assertEquals(4000L, result.canonicalWorkoutTrimps[1].endTimeMs)
        assertEquals(35f, result.canonicalWorkoutTrimps[1].trimp, 0.001f)
    }
}
