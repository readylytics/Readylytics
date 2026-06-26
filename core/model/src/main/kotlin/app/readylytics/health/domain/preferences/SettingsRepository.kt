package app.readylytics.health.domain.preferences

import app.readylytics.health.data.preferences.UserPreferences
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface SettingsRepository {
    val userPreferences: Flow<UserPreferences>
    suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean)
    suspend fun updateMaxHeartRate(bpm: Int)
    suspend fun migrateDeviceSelectionIfNeeded()
    suspend fun updateLastSyncTimestamp(timestamp: Long)
    suspend fun updateBirthday(date: LocalDate)
}
