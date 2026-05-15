package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import kotlinx.coroutines.flow.first

private const val TAG = "MediumWidgetUpdater"
private const val DEFAULT_STEPS_GOAL = 10000L

/**
 * Helper to update medium widget state from data sources.
 * Handles both DUAL_METRIC and STEPS_PROGRESS modes.
 */
object MediumWidgetUpdater {
    suspend fun updateMediumWidget(
        context: Context,
        widgetId: Int,
        widgetDataRepository: WidgetDataRepository,
        configRepository: WidgetConfigurationRepository,
    ) {
        try {
            // Load widget configuration
            val config =
                configRepository.observeMediumWidgetConfig(widgetId).first()
                    ?: return

            val mode =
                try {
                    WidgetMode.valueOf(config.mode)
                } catch (e: IllegalArgumentException) {
                    WidgetMode.DUAL_METRIC
                }

            // Load latest summary
            val summary =
                widgetDataRepository.getLatestSummaryAsync()
                    ?: run {
                        saveWidgetError(context, widgetId, "No data available")
                        return
                    }

            when (mode) {
                WidgetMode.DUAL_METRIC -> {
                    updateDualMetricMode(context, widgetId, config, summary)
                }
                WidgetMode.STEPS_PROGRESS -> {
                    updateStepsProgressMode(context, widgetId, summary)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update medium widget $widgetId", e)
            saveWidgetError(context, widgetId, e.message ?: "Unknown error")
        }
    }

    private suspend fun updateDualMetricMode(
        context: Context,
        widgetId: Int,
        config: com.gregor.lauritz.healthdashboard.data.repository.MediumWidgetConfig,
        summary: com.gregor.lauritz.healthdashboard.domain.model.DailySummary,
    ) {
        val metric1Type =
            try {
                MetricType.valueOf(config.metric1 ?: "HRV")
            } catch (e: IllegalArgumentException) {
                MetricType.HRV
            }

        val metric2Type =
            try {
                MetricType.valueOf(config.metric2 ?: "RHR")
            } catch (e: IllegalArgumentException) {
                MetricType.RHR
            }

        val (metric1Value, metric1Status) = WidgetMetricExtractor.extractMetricData(metric1Type, summary)
        val (metric2Value, metric2Status) = WidgetMetricExtractor.extractMetricData(metric2Type, summary)

        WidgetDataStoreProvider.getDataStore(context).edit { preferences ->
            preferences[MediumWidgetKeys.mode(widgetId)] = WidgetMode.DUAL_METRIC.name
            preferences[MediumWidgetKeys.metric1Type(widgetId)] = metric1Type.name
            preferences[MediumWidgetKeys.metric1Value(widgetId)] = metric1Value
            preferences[MediumWidgetKeys.metric1Status(widgetId)] = metric1Status.name
            preferences[MediumWidgetKeys.metric2Type(widgetId)] = metric2Type.name
            preferences[MediumWidgetKeys.metric2Value(widgetId)] = metric2Value
            preferences[MediumWidgetKeys.metric2Status(widgetId)] = metric2Status.name
            preferences[MediumWidgetKeys.lastUpdate(widgetId)] = System.currentTimeMillis()
        }
    }

    private suspend fun updateStepsProgressMode(
        context: Context,
        widgetId: Int,
        summary: com.gregor.lauritz.healthdashboard.domain.model.DailySummary,
    ) {
        val currentSteps = (summary.stepCount ?: 0).toLong()

        WidgetDataStoreProvider.getDataStore(context).edit { preferences ->
            preferences[MediumWidgetKeys.mode(widgetId)] = WidgetMode.STEPS_PROGRESS.name
            preferences[MediumWidgetKeys.currentSteps(widgetId)] = currentSteps
            preferences[MediumWidgetKeys.goalSteps(widgetId)] = DEFAULT_STEPS_GOAL
            preferences[MediumWidgetKeys.lastUpdate(widgetId)] = System.currentTimeMillis()
        }
    }

    private suspend fun saveWidgetError(
        context: Context,
        widgetId: Int,
        error: String,
    ) {
        WidgetDataStoreProvider.getDataStore(context).edit { preferences ->
            preferences[MediumWidgetKeys.error(widgetId)] = error
            preferences[MediumWidgetKeys.mode(widgetId)] = WidgetMode.DUAL_METRIC.name
        }
    }
}
