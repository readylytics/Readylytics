package app.readylytics.health.domain.scoring

fun calculateDailyStrainIncrease(
    dataTenureDays: Int,
    loadSourceMode: LoadSourceMode,
    workoutOnlyGains: List<Float>,
    strainRatioWithDay: Float?,
    strainRatioWithoutDay: Float?,
): Float? =
    if (dataTenureDays < 7) {
        null
    } else {
        when (loadSourceMode) {
            LoadSourceMode.WORKOUT_ONLY -> workoutOnlyGains.sum()
            LoadSourceMode.EVERYDAY_HEART_RATE ->
                if (strainRatioWithDay != null && strainRatioWithoutDay != null) {
                    (strainRatioWithDay - strainRatioWithoutDay).coerceAtLeast(0f)
                } else {
                    null
                }
        }
    }
