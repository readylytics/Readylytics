package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import kotlinx.coroutines.flow.first

private const val TAG = "MediumWidgetUpdater"

private val Context.glanceMediumWidgetDataStore by preferencesDataStore(
    name = "glance_medium_widget_data"
)

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
            val config = configRepository.observeMediumWidgetConfig(widgetId).first()
                ?: return

            val mode = try {
                WidgetMode.valueOf(config.mode)
            } catch (e: Exception) {
                WidgetMode.DUAL_METRIC
            }

            // Load latest summary
            val summary = widgetDataRepository.getLatestSummaryAsync()
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
        val metric1Type = try {
            MetricType.valueOf(config.metric1 ?: "HRV")
        } catch (e: Exception) {
            MetricType.HRV
        }

        val metric2Type = try {
            MetricType.valueOf(config.metric2 ?: "RHR")
        } catch (e: Exception) {
            MetricType.RHR
        }

        val (metric1Value, metric1Status) = WidgetMetricExtractor.extractMetricData(metric1Type, summary)
        val (metric2Value, metric2Status) = WidgetMetricExtractor.extractMetricData(metric2Type, summary)

        context.glanceMediumWidgetDataStore.edit { preferences ->
            val widgetIdStr = widgetId.toString()
            preferences[MediumWidgetData.createModeKey(widgetId, "mode")] =
                WidgetMode.DUAL_METRIC.name
            preferences[MediumWidgetData.createModeKey(widgetId, "metric1_type")] =
                metric1Type.name
            preferences[MediumWidgetData.createDoubleKey(widgetId, "metric1_value")] =
                metric1Value
            preferences[MediumWidgetData.createModeKey(widgetId, "metric1_status")] =
                metric1Status.name
            preferences[MediumWidgetData.createModeKey(widgetId, "metric2_type")] =
                metric2Type.name
            preferences[MediumWidgetData.createDoubleKey(widgetId, "metric2_value")] =
                metric2Value
            preferences[MediumWidgetData.createModeKey(widgetId, "metric2_status")] =
                metric2Status.name
            preferences[MediumWidgetData.createLongKey(widgetId, "last_update")] =
                System.currentTimeMillis()
        }

        GlanceAppWidgetManager(context).updateAll(MediumWidget::class.java)
    }

    private suspend fun updateStepsProgressMode(
        context: Context,
        widgetId: Int,
        summary: com.gregor.lauritz.healthdashboard.domain.model.DailySummary,
    ) {
        val currentSteps = (summary.stepCount ?: 0).toLong()
        val goalSteps = 10000L

        context.glanceMediumWidgetDataStore.edit { preferences ->
            preferences[MediumWidgetData.createModeKey(widgetId, "mode")] =
                WidgetMode.STEPS_PROGRESS.name
            preferences[MediumWidgetData.createLongKey(widgetId, "current_steps")] =
                currentSteps
            preferences[MediumWidgetData.createLongKey(widgetId, "goal_steps")] = goalSteps
            preferences[MediumWidgetData.createLongKey(widgetId, "last_update")] =
                System.currentTimeMillis()
        }

        GlanceAppWidgetManager(context).updateAll(MediumWidget::class.java)
    }

    private suspend fun saveWidgetError(
        context: Context,
        widgetId: Int,
        error: String,
    ) {
        context.glanceMediumWidgetDataStore.edit { preferences ->
            preferences[MediumWidgetData.createModeKey(widgetId, "error")] = error
            preferences[MediumWidgetData.createModeKey(widgetId, "mode")] =
                WidgetMode.DUAL_METRIC.name
        }

        GlanceAppWidgetManager(context).updateAll(MediumWidget::class.java)
    }

}
