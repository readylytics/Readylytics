package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutData
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class ComputeWorkoutLoadMetricsUseCaseTest {
    private val computeWorkoutTrimpUseCase = mockk<ComputeWorkoutTrimpUseCase>()
    private val scoringCalculator = mockk<ScoringCalculator>()
    private val useCase = ComputeWorkoutLoadMetricsUseCase(computeWorkoutTrimpUseCase, scoringCalculator)

    @Test
    fun `uses stored workout trimp and returns rounded display values`() {
        val workout =
            WorkoutData(
                id = "run-1",
                startTime = 1_000L,
                endTime = 3_601_000L,
                exerciseType = "RUNNING",
                durationMinutes = 60,
                zone1Minutes = 0f,
                zone2Minutes = 10f,
                zone3Minutes = 20f,
                zone4Minutes = 30f,
                zone5Minutes = 0f,
                trimp = 115.6f,
                avgHr = 134f,
            )
        val workoutDate = LocalDate.of(2026, 6, 9)
        val trimpByDate = mapOf(workoutDate to 115.6f)

        every {
            computeWorkoutTrimpUseCase.execute(
                workoutStartTime = workout.startTime,
                workoutEndTime = workout.endTime,
                workoutAvgHr = workout.avgHr,
                samples = emptyList(),
                prefs = any(),
                restingHrBaseline = 52f,
                storedTrimp = workout.trimp,
            )
        } returns Result.success(115.6f)
        every { scoringCalculator.computeAtlEmaWithDecay(any(), workoutDate, any()) } returnsMany listOf(2f, 1f)
        every { scoringCalculator.computeCtlEmaWithDecay(any(), workoutDate, any()) } returnsMany listOf(1f, 1f)
        every { scoringCalculator.computeStrainRatio(any(), any()) } returnsMany listOf(1.365f, 1.0f)

        val result =
            useCase.execute(
                workout = workout,
                workoutDate = workoutDate,
                samples = emptyList(),
                prefs = UserPreferences(),
                restingHrBaseline = 52f,
                trimpByDate = trimpByDate,
            )

        assertEquals(115.6f, result.preciseTrimp)
        assertEquals(116, result.roundedTrimp)
        assertEquals(0.365f, result.preciseGainedStrain)
        assertEquals(0.37f, result.roundedGainedStrain)
        assertEquals("0.37", result.gainedStrainDisplay)
        verify {
            computeWorkoutTrimpUseCase.execute(
                workoutStartTime = workout.startTime,
                workoutEndTime = workout.endTime,
                workoutAvgHr = workout.avgHr,
                samples = emptyList(),
                prefs = any(),
                restingHrBaseline = 52f,
                storedTrimp = workout.trimp,
            )
        }
    }
}
