package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import kotlinx.coroutines.flow.first

private const val TAG = "SmallWidgetUpdater"

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
            val config =
                configRepository.observeSmallWidgetConfig(widgetId).first()
                    ?: return

            val metricType = MetricType.valueOf(config.metricType)

            // Load latest summary
            val summary =
                widgetDataRepository.getLatestSummaryAsync()
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
        WidgetDataStoreProvider.getDataStore(context).edit { preferences ->
            preferences[SmallWidgetKeys.metricType(widgetId)] = metricType.name
            preferences[SmallWidgetKeys.status(widgetId)] = status
            preferences[SmallWidgetKeys.lastUpdate(widgetId)] = System.currentTimeMillis()

            value?.let {
                preferences[SmallWidgetKeys.value(widgetId)] = it
            }
            trend?.let {
                preferences[SmallWidgetKeys.trend(widgetId)] = it
            }
        }
    }

    private suspend fun saveWidgetError(
        context: Context,
        widgetId: Int,
        error: String,
    ) {
        WidgetDataStoreProvider.getDataStore(context).edit { preferences ->
            preferences[SmallWidgetKeys.error(widgetId)] = error
            preferences[SmallWidgetKeys.status(widgetId)] = "CALIBRATING"
        }
    }
}
