package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CardConfigurationSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun serialize(configurations: List<CardConfiguration>): String {
        // Serialize card configurations to JSON string for DataStore persistence
        return try {
            json.encodeToString(configurations)
        } catch (e: SerializationException) {
            // Return empty string if serialization fails to prevent data loss
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
                // Return empty list if deserialization fails; user can reset to defaults
                emptyList()
            }
        }
    }
}
