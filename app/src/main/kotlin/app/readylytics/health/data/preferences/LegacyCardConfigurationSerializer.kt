package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logW
import app.readylytics.health.domain.dashboard.CardConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LegacyCardConfigurationSerializer {
    private const val TAG = "LegacyCardConfigurationSerializer"

    // ignoreUnknownKeys=true enables graceful handling of new properties in future app versions
    // allowing older saved configs to load without errors when app adds new card types.
    // coerceInputValues=true keeps decoding tolerant when a stored field's value doesn't match
    // the expected shape (e.g. malformed data), falling back to each property's declared
    // default rather than rejecting the whole card list. Scoped narrowly to this legacy card
    // JSON path only; other entity/JSON decoding in the app is intentionally left strict.
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    fun serialize(configurations: List<CardConfiguration>): String {
        // Serialize card configurations to JSON string for DataStore persistence
        return try {
            json.encodeToString(configurations)
        } catch (e: SerializationException) {
            logE(TAG, e) { "Failed to serialize card configurations" }
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
                logW(TAG, e) { "Failed to deserialize card configurations, using defaults" }
                // Return empty list on error; SettingsDefaults will provide default card layout
                emptyList()
            }
        }
    }
}
