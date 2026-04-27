package com.gregor.lauritz.healthdashboard.ui.workouts

import org.junit.Test

class WorkoutDetailScreenTest {
    @Test
    fun hrChart_variousWorkoutDurations_labelCountAtMostSix() {
        // Formula: spacing = max(1, (durationMinutes + 4) / 5)
        // This is equivalent to ceil(durationMinutes / 5.0)
        // Number of labels (including 0) is roughly (durationMinutes / spacing) + 1

        val testCases = listOf(
            5, 10, 15, 20, 25, 30, 45, 60, 90, 120, 300, 1440
        )

        testCases.forEach { durationMinutes ->
            val spacing = maxOf(1, (durationMinutes + 4) / 5)
            val labels = if (durationMinutes == 0) 1 else (durationMinutes / spacing) + 1

            println("Duration: $durationMinutes min, Spacing: $spacing, Estimated Labels: $labels")

            assert(labels <= 6) {
                "For $durationMinutes min with spacing $spacing: got $labels labels (expected <= 6)"
            }
        }
    }
}
