package com.gregor.lauritz.healthdashboard.data.preferences

import kotlinx.coroutines.flow.Flow

interface CircadianThresholdPreferences {
    val overrideMinutesFlow: Flow<Int?>

    suspend fun setOverride(minutes: Int?)
}
