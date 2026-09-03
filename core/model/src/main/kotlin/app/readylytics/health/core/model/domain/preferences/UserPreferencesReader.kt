package app.readylytics.health.core.model.domain.preferences

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import kotlinx.coroutines.flow.Flow

interface UserPreferencesReader {
    val userPreferences: Flow<UserPreferences>
}
