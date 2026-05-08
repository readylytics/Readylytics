package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.state.GlanceStateDefinition
import java.io.File

/**
 * Glance state definition for small widget.
 * Manages widget data persistence in DataStore.
 *
 * State includes:
 * - Metric type (what to display)
 * - Current value
 * - Status (optimal/neutral/warning/poor)
 * - Trend (↑/→/↓)
 * - Last update timestamp
 * - Error state
 */
data class SmallWidgetData(
    val metricType: String = "HRV",
    val value: Double = 0.0,
    val status: String = "CALIBRATING",
    val trend: Double? = null,
    val lastUpdateMs: Long = 0L,
    val error: String? = null,
) {
    companion object {
        val METRIC_TYPE_KEY = stringPreferencesKey("metric_type")
        val VALUE_KEY = doublePreferencesKey("value")
        val STATUS_KEY = stringPreferencesKey("status")
        val TREND_KEY = doublePreferencesKey("trend")
        val LAST_UPDATE_KEY = longPreferencesKey("last_update")
        val ERROR_KEY = stringPreferencesKey("error")
    }
}

/**
 * GlanceStateDefinition for SmallWidget.
 * Uses DataStore under the hood via Glance's PreferencesGlanceStateDefinition.
 *
 * Note: For now, we'll use the default PreferencesGlanceStateDefinition
 * and manually manage state. In a production app, we'd create a custom
 * GlanceStateDefinition if needed.
 */
class SmallWidgetStateDefinition : GlanceStateDefinition<SmallWidgetData> {
    override suspend fun getDataStore(
        context: Context,
        fileKey: String,
    ): DataStore<Preferences> {
        // Use Glance's built-in DataStore management
        return androidx.glance.state.PreferencesGlanceStateDefinition().getDataStore(
            context,
            fileKey,
        )
    }

    override fun getLocation(context: Context, fileKey: String): File {
        return File(context.filesDir, "glance_small_widget_$fileKey")
    }
}
