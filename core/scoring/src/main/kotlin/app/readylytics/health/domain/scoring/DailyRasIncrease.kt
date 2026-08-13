package app.readylytics.health.domain.scoring

fun calculateDailyRasIncrease(
    dataTenureDays: Int,
    todayRas: Float?,
    yesterdayRas: Float?,
): Float? =
    if (dataTenureDays < 7) {
        null
    } else {
        if (todayRas != null && yesterdayRas != null) {
            (todayRas - yesterdayRas).coerceAtLeast(0f)
        } else {
            null
        }
    }
