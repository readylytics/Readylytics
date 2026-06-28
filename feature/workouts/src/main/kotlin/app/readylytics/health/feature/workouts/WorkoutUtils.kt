package app.readylytics.health.feature.workouts

fun exerciseTypeToDisplayName(type: String): String =
    when (type) {
        "56" -> "Running"
        "79" -> "Walking"
        "8" -> "Cycling"
        "74",
        "73",
        -> "Swimming"
        "70" -> "Strength"
        "37" -> "Hiking"
        "83" -> "Yoga"
        "48" -> "Pilates"
        "25" -> "Elliptical"
        "54" -> "Rowing"
        "68",
        "69",
        -> "Stairs"
        "36" -> "HIIT"
        else ->
            type
                .replace("EXERCISE_TYPE_", "")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
                .replace("_", " ")
    }
