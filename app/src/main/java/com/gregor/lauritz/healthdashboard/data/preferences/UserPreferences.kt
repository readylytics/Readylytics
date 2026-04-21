package com.gregor.lauritz.healthdashboard.data.preferences

data class UserPreferences(
    val goalSleepHours: Float = 8f,
    val hrvBaselineOverride: Float? = null,
    val rhrBaselineOverride: Float? = null,
    val syncPreference: SyncPreference = SyncPreference.BY_TIME,
    val syncIntervalHours: Int = 1,
    val lastSyncTimestamp: Long = 0L,
    val maxHeartRate: Int = 190,
)
