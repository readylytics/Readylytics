package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CardConfigurationSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun serialize(configurations: List<CardConfiguration>): String {
        return try {
            json.encodeToString(configurations)
        } catch (e: Exception) {
            ""
        }
    }

    fun deserialize(jsonString: String): List<CardConfiguration> {
        return if (jsonString.isEmpty()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<CardConfiguration>>(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
