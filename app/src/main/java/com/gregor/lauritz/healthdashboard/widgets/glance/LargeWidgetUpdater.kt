package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import kotlinx.coroutines.flow.first

private const val TAG = "LargeWidgetUpdater"

private val Context.glanceLargeWidgetDataStore by preferencesDataStore(
    name = "glance_large_widget_data"
)

/**
 * Helper to update large widget state from data sources.
 * Loads up to 4 metrics and updates DataStore.
 */
object LargeWidgetUpdater {
    suspend fun updateLargeWidget(
        context: Context,
        widgetId: Int,
        widgetDataRepository: WidgetDataRepository,
        configRepository: WidgetConfigurationRepository,
    ) {
        try {
            // Load widget configuration
            val config = configRepository.observeLargeWidgetConfig(widgetId).first()
                ?: return

            // Load latest summary
            val summary = widgetDataRepository.getLatestSummaryAsync()
                ?: run {
                    saveWidgetError(context, widgetId, "No data available")
                    return
                }

            // Default 4-card layout if not configured
            val cardIds = if (config.cardIds.isEmpty()) {
                listOf("SLEEP_SCORE", "READINESS", "HRV", "STEPS")
            } else {
                config.cardIds.take(4) // Limit to 4 cards
            }

            // Update each card
            saveWidgetState(context, widgetId, cardIds, summary)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update large widget $widgetId", e)
            saveWidgetError(context, widgetId, e.message ?: "Unknown error")
        }
    }

    private suspend fun saveWidgetState(
        context: Context,
        widgetId: Int,
        cardIds: List<String>,
        summary: com.gregor.lauritz.healthdashboard.domain.model.DailySummary,
    ) {
        context.glanceLargeWidgetDataStore.edit { preferences ->
            cardIds.forEachIndexed { index, cardId ->
                val cardNum = index + 1
                try {
                    val (value, status, cardType) = extractCardData(cardId, summary)

                    preferences[LargeWidgetData.createStringKey(widgetId, "card${cardNum}_type")] =
                        cardType
                    preferences[LargeWidgetData.createStringKey(widgetId, "card${cardNum}_metric")] =
                        cardId
                    preferences[LargeWidgetData.createDoubleKey(widgetId, "card${cardNum}_value")] =
                        value
                    preferences[LargeWidgetData.createStringKey(widgetId, "card${cardNum}_status")] =
                        status.name
                } catch (e: Exception) {
                    // Skip invalid cards
                }
            }

            preferences[LargeWidgetData.createLongKey(widgetId, "last_update")] =
                System.currentTimeMillis()
        }

        GlanceAppWidgetManager(context).updateAll(LargeWidget::class.java)
    }

    private suspend fun saveWidgetError(
        context: Context,
        widgetId: Int,
        error: String,
    ) {
        context.glanceLargeWidgetDataStore.edit { preferences ->
            preferences[LargeWidgetData.createStringKey(widgetId, "error")] = error
        }

        GlanceAppWidgetManager(context).updateAll(LargeWidget::class.java)
    }

    private fun extractCardData(
        cardId: String,
        summary: com.gregor.lauritz.healthdashboard.domain.model.DailySummary,
    ): Triple<Double, MetricStatus, String> {
        // Determine card type and extract data
        val metricType = try {
            MetricType.valueOf(cardId)
        } catch (e: Exception) {
            return Triple(0.0, MetricStatus.CALIBRATING, "METRIC")
        }

        val cardType = when (metricType) {
            MetricType.STEPS -> "STEPS"
            MetricType.SLEEP_SCORE, MetricType.READINESS -> "SCORE"
            else -> "METRIC"
        }

        val (value, status) = WidgetMetricExtractor.extractMetricData(metricType, summary)
        return Triple(value, status, cardType)
    }

}
