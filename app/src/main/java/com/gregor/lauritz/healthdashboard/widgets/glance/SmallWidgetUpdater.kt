package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
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
            val (value, status) = extractMetricData(metricType, summary)

            // Save to DataStore
            saveWidgetState(context, widgetId, metricType, value, status.name, trend = null)

        } catch (e: Exception) {
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

    private fun extractMetricData(
        type: MetricType,
        summary: com.gregor.lauritz.healthdashboard.domain.model.DailySummary,
    ): Pair<Double, MetricStatus> {
        return when (type) {
            MetricType.HRV -> {
                val value = summary.nocturnalHrv?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = summary.diagnostics?.hrvStatus ?: MetricStatus.CALIBRATING
                value to status
            }
            MetricType.RHR -> {
                val value = summary.nocturnalRhr?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = summary.diagnostics?.rhrStatus ?: MetricStatus.CALIBRATING
                value to status
            }
            MetricType.SLEEP_SCORE -> {
                val value = summary.sleepScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.READINESS -> {
                val value = summary.readinessScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.RECOVERY -> {
                val value = summary.readinessScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = if (value > 0) MetricStatus.OPTIMAL else MetricStatus.CALIBRATING
                value to status
            }
            MetricType.SLEEP_DURATION -> {
                val minutes = summary.sleepDurationMinutes ?: return 0.0 to MetricStatus.CALIBRATING
                val value = minutes / 60.0
                value to MetricStatus.NEUTRAL
            }
            MetricType.SLEEP_EFFICIENCY -> {
                val efficiency = (summary.deepSleepPercent?.plus(summary.remSleepPercent ?: 0f) ?: 0f).toDouble()
                val status = when {
                    efficiency > 85 -> MetricStatus.OPTIMAL
                    efficiency > 70 -> MetricStatus.NEUTRAL
                    efficiency > 50 -> MetricStatus.WARNING
                    else -> MetricStatus.CALIBRATING
                }
                efficiency to status
            }
            MetricType.STEPS -> {
                val value = summary.stepCount?.toDouble() ?: return 0.0 to MetricStatus.NEUTRAL
                value to MetricStatus.NEUTRAL
            }
            MetricType.PAI -> {
                val value = summary.paiScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                val status = when {
                    value >= 100 -> MetricStatus.OPTIMAL
                    value >= 75 -> MetricStatus.NEUTRAL
                    value >= 50 -> MetricStatus.WARNING
                    else -> MetricStatus.POOR
                }
                value to status
            }
            MetricType.STRAIN_RATIO -> {
                val value = summary.strainRatio ?: return 0.0 to MetricStatus.CALIBRATING
                val status = when {
                    value in 0.8f..1.3f -> MetricStatus.OPTIMAL
                    value < 0.8 -> MetricStatus.WARNING
                    else -> MetricStatus.WARNING
                }
                value.toDouble() to status
            }
            MetricType.BODY_BATTERY -> {
                val value = summary.readinessScore?.toDouble() ?: return 0.0 to MetricStatus.CALIBRATING
                value to if (value > 50) MetricStatus.OPTIMAL else MetricStatus.WARNING
            }
            MetricType.STRESS -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.CIRCADIAN_CONSISTENCY -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.VO2_MAX -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.WEIGHT -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
            MetricType.CALORIES -> {
                return 0.0 to MetricStatus.CALIBRATING
            }
        }
    }
}

// Helper imports
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
