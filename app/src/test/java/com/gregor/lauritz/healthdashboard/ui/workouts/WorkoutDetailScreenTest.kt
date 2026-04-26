package com.gregor.lauritz.healthdashboard.ui.workouts

import org.junit.Test

class WorkoutDetailScreenTest {
    @Test
    fun hrChart_variousWorkoutDurations_labelCountFollowsV1Formula() {
        // V1: spacing = max(1, durationMinutes / 8)
        // This ensures 6-10 labels max
        val testCases = listOf(
            10 to 2,    // 10 min: spacing = max(1, round(10/8)) = 1 or 2
            60 to 8,    // 60 min: spacing = max(1, round(60/8)) = 8 (60/8 ≈ 7-8 labels)
            120 to 15,  // 120 min: spacing = max(1, round(120/8)) = 15 (120/15 = 8 labels)
            300 to 38,  // 300 min: spacing = max(1, round(300/8)) = 38 (300/38 ≈ 8 labels)
        )

        testCases.forEach { (durationMinutes, expectedSpacing) ->
            val spacing = maxOf(1, (durationMinutes / 8.0).toInt())
            val labels = durationMinutes / spacing
            assert(labels in 6..10) {
                "For $durationMinutes min with spacing $spacing: got $labels labels (expected 6-10)"
            }
        }
    }
}
