package app.readylytics.health.data.preferences

import android.util.Log
import app.readylytics.health.domain.dashboard.CardConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LegacyCardConfigurationSerializer {
    private const val TAG = "LegacyCardConfigurationSerializer"

    // ignoreUnknownKeys=true enables graceful handling of new properties in future app versions
    // allowing older saved configs to load without errors when app adds new card types
    private val json = Json { ignoreUnknownKeys = true }

    fun serialize(configurations: List<CardConfiguration>): String {
        // Serialize card configurations to JSON string for DataStore persistence
        return try {
            json.encodeToString(configurations)
        } catch (e: SerializationException) {
            Log.e(TAG, "Failed to serialize card configurations: ${e.message}", e)
            // Return empty string on failure; ReorderableCardGrid will filter out missing cards
            ""
        }
    }

    fun deserialize(jsonString: String): List<CardConfiguration> {
        // Deserialize JSON string back to CardConfiguration list
        return if (jsonString.isEmpty()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<CardConfiguration>>(jsonString)
            } catch (e: SerializationException) {
                Log.w(TAG, "Failed to deserialize card configurations, using defaults: ${e.message}")
                // Return empty list on error; SettingsDefaults will provide default card layout
                emptyList()
            }
        }
    }
}
