package app.readylytics.health.domain.preferences

import app.readylytics.health.data.preferences.UserPreferences
import kotlinx.coroutines.flow.Flow

interface UserPreferencesReader {
    val userPreferences: Flow<UserPreferences>
}
