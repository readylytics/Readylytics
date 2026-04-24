package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.health.connect.client.records.ExerciseSessionRecord

fun exerciseTypeToDisplayName(type: String): String =
    when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING.toString() -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING.toString() -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING.toString() -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL.toString(),
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER.toString(),
        -> "Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING.toString() -> "Strength"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING.toString() -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA.toString() -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES.toString() -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL.toString() -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE.toString() -> "Rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING.toString(),
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE.toString(),
        -> "Stairs"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING.toString() -> "HIIT"
        else ->
            type
                .replace("EXERCISE_TYPE_", "")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
                .replace("_", " ")
    }
