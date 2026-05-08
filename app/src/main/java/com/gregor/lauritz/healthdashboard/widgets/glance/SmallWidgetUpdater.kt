package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetData.Companion.ERROR_KEY
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetData.Companion.LAST_UPDATE_KEY
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetData.Companion.METRIC_TYPE_KEY
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetData.Companion.STATUS_KEY
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetData.Companion.TREND_KEY
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetData.Companion.VALUE_KEY
import kotlinx.coroutines.flow.first

private const val TAG = "SmallWidgetUpdater"

private val Context.glanceDataStore by preferencesDataStore(
    name = "glance_small_widget_data"
)

/**
 * Helper to update small widget state from data sources.
 * Called from WidgetUpdateBroadcastReceiver and WidgetUpdateWorker.
 */
object SmallWidgetUpdater {
    suspend fun updateSmallWidget(
        context: Context,
        widgetId: Int,
        widgetDataRepository: WidgetDataRepository,
        configRepository: WidgetConfigurationRepository,
    ) {
        try {
            // Load widget configuration
            val config = configRepository.observeSmallWidgetConfig(widgetId).first()
                ?: return

            val metricType = MetricType.valueOf(config.metricType)

            // Load latest summary
            val summary = widgetDataRepository.getLatestSummaryAsync()
                ?: run {
                    // No data available
                    saveWidgetState(context, widgetId, metricType, null, "No data available")
                    return
                }

            // Extract metric value and calculate status
            val (value, status) = WidgetMetricExtractor.extractMetricData(metricType, summary)

            // Save to DataStore
            saveWidgetState(context, widgetId, metricType, value, status.name, trend = null)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update small widget $widgetId", e)
            saveWidgetError(context, widgetId, e.message ?: "Unknown error")
        }
    }

    private suspend fun saveWidgetState(
        context: Context,
        widgetId: Int,
        metricType: MetricType,
        value: Double?,
        status: String,
        trend: Double? = null,
    ) {
        context.glanceDataStore.edit { preferences ->
            val prefix = "widget_${widgetId}_"
            preferences[stringPreferencesKey("${prefix}metric_type")] = metricType.name
            preferences[stringPreferencesKey("${prefix}status")] = status
            preferences[longPreferencesKey("${prefix}last_update")] = System.currentTimeMillis()

            value?.let {
                preferences[doublePreferencesKey("${prefix}value")] = it
            }
            trend?.let {
                preferences[doublePreferencesKey("${prefix}trend")] = it
            }
        }

        // Trigger widget update
        GlanceAppWidgetManager(context).updateAll(SmallWidget::class.java)
    }

    private suspend fun saveWidgetError(
        context: Context,
        widgetId: Int,
        error: String,
    ) {
        context.glanceDataStore.edit { preferences ->
            val prefix = "widget_${widgetId}_"
            preferences[stringPreferencesKey("${prefix}error")] = error
            preferences[stringPreferencesKey("${prefix}status")] = "CALIBRATING"
        }

        GlanceAppWidgetManager(context).updateAll(SmallWidget::class.java)
    }

}

// Helper imports
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
