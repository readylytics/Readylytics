package com.gregor.lauritz.healthdashboard.data.preferences

interface CircadianThresholdPreferences {
    val overrideMinutes: Int?

    suspend fun setOverride(minutes: Int?)
}
