package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import kotlinx.coroutines.flow.first

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

        val (metric1Value, metric1Status) = extractMetricData(metric1Type, summary)
        val (metric2Value, metric2Status) = extractMetricData(metric2Type, summary)

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
        val currentSteps = summary.stepCount
        val goalSteps = 10000L // Default goal, could be customizable

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

    private fun extractMetricData(
        type: MetricType,
        summary: com.gregor.lauritz.healthdashboard.domain.model.DailySummary,
    ): Pair<Double, MetricStatus> {
        // Same logic as SmallWidgetUpdater
        return when (type) {
            MetricType.HRV -> {
                val value = summary.nocturnalHrv.toDouble()
                val status = summary.diagnostics?.hrvStatus ?: MetricStatus.CALIBRATING
                value to status
            }
            MetricType.RHR -> {
                val value = summary.nocturnalRhr.toDouble()
                val status = summary.diagnostics?.rhrStatus ?: MetricStatus.CALIBRATING
                value to status
            }
            MetricType.SLEEP_SCORE -> {
                val value = summary.sleepScore.toDouble()
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.READINESS -> {
                val value = summary.readinessScore.toDouble()
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.SLEEP_DURATION -> {
                val value = (summary.sleepDurationMinutes / 60.0)
                val status = MetricStatus.NEUTRAL
                value to status
            }
            MetricType.STEPS -> {
                val value = summary.stepCount.toDouble()
                val status = MetricStatus.NEUTRAL
                value to status
            }
            MetricType.PAI -> {
                val value = summary.paiScore.toDouble()
                val status = when {
                    value >= 100 -> MetricStatus.OPTIMAL
                    value >= 75 -> MetricStatus.NEUTRAL
                    value >= 50 -> MetricStatus.WARNING
                    else -> MetricStatus.POOR
                }
                value to status
            }
            MetricType.STRAIN_RATIO -> {
                val value = summary.strainRatio
                val status = when {
                    value in 0.8..1.3 -> MetricStatus.OPTIMAL
                    value < 0.8 -> MetricStatus.WARNING
                    else -> MetricStatus.WARNING
                }
                value to status
            }
            else -> {
                0.0 to MetricStatus.CALIBRATING
            }
        }
    }
}
