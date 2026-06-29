package app.readylytics.health.domain.preferences

import kotlinx.coroutines.flow.Flow

interface CircadianThresholdPreferences {
    val overrideMinutesFlow: Flow<Int?>

    suspend fun setOverride(minutes: Int?)

    val isEncrypted: Boolean get() = true
}
