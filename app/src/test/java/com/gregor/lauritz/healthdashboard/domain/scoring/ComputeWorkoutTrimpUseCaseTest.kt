package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComputeWorkoutTrimpUseCaseTest {
    private val useCase = ComputeWorkoutTrimpUseCase()
    private val defaultPrefs =
        UserPreferences(
            gender = Gender.MALE,
            maxHeartRate = 200,
            rhrBaselineOverride = 60f,
            trimpModel = TrimpModel.BANISTER,
        )

    @Test
    fun emptyHrSamples_returnsTrimpBasedOnDurationAndAvgHr() {
        val startTime = 1000L * 60 * 60 // 1 hour in ms
        val endTime = startTime + 1000 * 60 * 30 // 30 mins later
        val avgHr = 150f

        val result =
            useCase.execute(
                workoutStartTime = startTime,
                workoutEndTime = endTime,
                workoutAvgHr = avgHr,
                samples = emptyList(),
                prefs = defaultPrefs,
            )

        // Expected TRIMP for 30 mins at 150 BPM with 60 RHR and 200 MaxHR (Male, Banister)
        // HRR = 140, hrR = (150-60)/140 = 0.6428
        // TRIMP = 30 * 0.6428 * 0.64 * exp(1.92 * 0.6428) = 19.284 * 3.435 = ~42
        assertTrue(result > 0)
        assertEquals(42.5f, result, 0.5f)
    }

    @Test
    fun singleIntervalTrimp_calculatesCorrectly() {
        val startTime = 0L
        val endTime = 1000L * 60 * 10 // 10 mins
        val samples =
            listOf(
                ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(0), 160),
            )

        val result =
            useCase.execute(
                workoutStartTime = startTime,
                workoutEndTime = endTime,
                workoutAvgHr = 160f,
                samples = samples,
                prefs = defaultPrefs,
            )

        // 10 mins at 160 BPM
        // hrR = (160-60)/140 = 0.714
        // TRIMP = 10 * 0.714 * 0.64 * exp(1.92 * 0.714) = 4.57 * 3.94 = ~18
        assertEquals(18.0f, result, 0.5f)
    }

    @Test
    fun multiZoneInterval_calculatesWeightedTrimp() {
        val startTime = 0L
        val endTime = 1000L * 60 * 20 // 20 mins total
        val samples =
            listOf(
                ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(0), 130), // 0-10 min
                ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(1000L * 60 * 10), 170), // 10-20 min
            )

        val result =
            useCase.execute(
                workoutStartTime = startTime,
                workoutEndTime = endTime,
                workoutAvgHr = 150f,
                samples = samples,
                prefs = defaultPrefs,
            )

        // 10 mins at 130 + 10 mins at 170
        // TRIMP(130) = 10 * 0.5 * 0.64 * exp(1.92 * 0.5) = 3.2 * 2.61 = 8.35
        // TRIMP(170) = 10 * 0.785 * 0.64 * exp(1.92 * 0.785) = 5.02 * 4.51 = 22.6
        // Total = ~31
        assertEquals(31.0f, result, 1.0f)
    }

    @Test
    fun leadingGapSamples_skippedCorrectly() {
        val startTime = 0L
        val firstSampleTime = 1000L * 60 * 5 // 5 min gap
        val endTime = 1000L * 60 * 15 // 15 min total

        val samples =
            listOf(
                ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(firstSampleTime), 150),
            )

        val result =
            useCase.execute(
                workoutStartTime = startTime,
                workoutEndTime = endTime,
                workoutAvgHr = 150f,
                samples = samples,
                prefs = defaultPrefs,
            )

        // Gap 0-5 min uses 150 (first sample)
        // Interval 5-15 min uses 150
        // Total 15 mins at 150
        // hrR = 0.6428, TRIMP(15 mins) = 15 * 0.6428 * 0.64 * exp(1.92 * 0.6428) = 21.25
        assertEquals(21.25f, result, 0.5f)
    }

    @Test
    fun filteredToEmpty_fallbackToZero() {
        val startTime = 1000L * 60 * 60
        val endTime = startTime + 1000L * 60 * 10
        val samples =
            listOf(
                ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(0), 180), // Outside range
            )

        val result =
            useCase.execute(
                workoutStartTime = startTime,
                workoutEndTime = endTime,
                workoutAvgHr = 150f,
                samples = samples,
                prefs = defaultPrefs,
            )

        // No samples in range, should fallback to duration * avgHr
        assertTrue(result > 0)
        assertEquals(14.1f, result, 0.5f)
    }

    @Test
    fun realisticWorkout_trimpInExpectedRange() {
        val startTime = 0L
        val endTime = 1000L * 60 * 60 // 1 hour
        val samples =
            (0..60 step 5).map { min ->
                ComputeWorkoutTrimpUseCase.HeartRateSample(
                    Instant.ofEpochMilli(min * 60_000L),
                    120 + (min % 30), // Varied HR
                )
            }

        val result =
            useCase.execute(
                workoutStartTime = startTime,
                workoutEndTime = endTime,
                workoutAvgHr = 135f,
                samples = samples,
                prefs = defaultPrefs,
            )

        // 1 hour steady state/varied cardio should be in 40-100 TRIMP range depending on intensity
        assertTrue(result in 40f..100f)
    }
}
