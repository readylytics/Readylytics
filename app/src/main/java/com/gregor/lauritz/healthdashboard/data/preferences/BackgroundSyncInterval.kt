package com.gregor.lauritz.healthdashboard.data.preferences

/**
 * User-selectable intervals for the periodic background Health Connect sync,
 * stored as [minutes] in [UserPreferencesProto.background_sync_interval_minutes].
 */
enum class BackgroundSyncInterval(
    val minutes: Int,
) {
    MINUTES_15(15),
    HOUR_1(60),
    HOURS_4(240),
    HOURS_12(720),
    DAILY(1440),
    ;

    companion object {
        fun fromMinutes(minutes: Int): BackgroundSyncInterval = entries.find { it.minutes == minutes } ?: HOUR_1
    }
}
