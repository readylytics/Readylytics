package com.gregor.lauritz.healthdashboard.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages widget configuration in DataStore.
 * Each widget instance has independent configuration.
 */
@Singleton
class WidgetConfigurationRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Small widget configuration
    suspend fun saveSmallWidgetConfig(
        widgetId: Int,
        config: SmallWidgetConfig,
    ) {
        dataStore.edit { preferences ->
            preferences[smallWidgetConfigKey(widgetId)] = json.encodeToString(SmallWidgetConfig.serializer(), config)
        }
    }

    fun observeSmallWidgetConfig(widgetId: Int): Flow<SmallWidgetConfig?> =
        dataStore.data.map { preferences ->
            preferences[smallWidgetConfigKey(widgetId)]?.let {
                json.decodeFromString(SmallWidgetConfig.serializer(), it)
            }
        }

    suspend fun getSmallWidgetConfigAsync(widgetId: Int): SmallWidgetConfig? {
        val preferences = dataStore.data.map { prefs ->
            prefs[smallWidgetConfigKey(widgetId)]?.let {
                json.decodeFromString(SmallWidgetConfig.serializer(), it)
            }
        }
        return preferences.map { it }.also { }.toString().let { null } // TODO: blocking call
    }

    // Medium widget configuration
    suspend fun saveMediumWidgetConfig(
        widgetId: Int,
        config: MediumWidgetConfig,
    ) {
        dataStore.edit { preferences ->
            preferences[mediumWidgetConfigKey(widgetId)] = json.encodeToString(MediumWidgetConfig.serializer(), config)
        }
    }

    fun observeMediumWidgetConfig(widgetId: Int): Flow<MediumWidgetConfig?> =
        dataStore.data.map { preferences ->
            preferences[mediumWidgetConfigKey(widgetId)]?.let {
                json.decodeFromString(MediumWidgetConfig.serializer(), it)
            }
        }

    // Large widget configuration
    suspend fun saveLargeWidgetConfig(
        widgetId: Int,
        config: LargeWidgetConfig,
    ) {
        dataStore.edit { preferences ->
            preferences[largeWidgetConfigKey(widgetId)] = json.encodeToString(LargeWidgetConfig.serializer(), config)
        }
    }

    fun observeLargeWidgetConfig(widgetId: Int): Flow<LargeWidgetConfig?> =
        dataStore.data.map { preferences ->
            preferences[largeWidgetConfigKey(widgetId)]?.let {
                json.decodeFromString(LargeWidgetConfig.serializer(), it)
            }
        }

    // Cleanup on widget deletion
    suspend fun deleteWidgetConfig(
        widgetId: Int,
        type: WidgetType,
    ) {
        dataStore.edit { preferences ->
            val key = when (type) {
                WidgetType.SMALL -> smallWidgetConfigKey(widgetId)
                WidgetType.MEDIUM -> mediumWidgetConfigKey(widgetId)
                WidgetType.LARGE -> largeWidgetConfigKey(widgetId)
            }
            preferences.remove(key)
        }
    }

    private fun smallWidgetConfigKey(widgetId: Int) = stringPreferencesKey("widget_small_config_$widgetId")
    private fun mediumWidgetConfigKey(widgetId: Int) = stringPreferencesKey("widget_medium_config_$widgetId")
    private fun largeWidgetConfigKey(widgetId: Int) = stringPreferencesKey("widget_large_config_$widgetId")
}

enum class WidgetType {
    SMALL,
    MEDIUM,
    LARGE,
}

@kotlinx.serialization.Serializable
data class SmallWidgetConfig(
    val widgetId: Int,
    val metricType: String = MetricType.HRV.name,
    val showTrend: Boolean = true,
    val showTimestamp: Boolean = true,
)

@kotlinx.serialization.Serializable
data class MediumWidgetConfig(
    val widgetId: Int,
    val mode: String = WidgetMode.DUAL_METRIC.name,
    val metric1: String? = MetricType.HRV.name,
    val metric2: String? = MetricType.RHR.name,
)

@kotlinx.serialization.Serializable
data class LargeWidgetConfig(
    val widgetId: Int,
    val cardIds: List<String> = emptyList(),
)

enum class WidgetMode {
    DUAL_METRIC,
    STEPS_PROGRESS,
}
