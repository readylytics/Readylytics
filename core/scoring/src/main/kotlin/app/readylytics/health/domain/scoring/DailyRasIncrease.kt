package app.readylytics.health.domain.scoring

fun calculateDailyRasIncrease(
    dataTenureDays: Int,
    todayRas: Float?,
): Float? =
    if (dataTenureDays < 7 || todayRas == null) {
        null
    } else {
        todayRas.coerceAtLeast(0f)
    }
