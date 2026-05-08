package com.gregor.lauritz.healthdashboard.widgets.glance

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey

/**
 * Data model for large widget (2x4).
 * Manages 4 cards in a 2x2 grid layout.
 *
 * Each card can be:
 * - Metric card (value + unit)
 * - Score dial (circular progress 0-100)
 * - Steps bar (progress bar)
 */
data class LargeWidgetData(
    // Grid positions (4 cards)
    val card1Type: String = "METRIC",
    val card1MetricType: String = "SLEEP_SCORE",
    val card1Value: Double = 0.0,
    val card1Status: String = "CALIBRATING",

    val card2Type: String = "METRIC",
    val card2MetricType: String = "READINESS",
    val card2Value: Double = 0.0,
    val card2Status: String = "CALIBRATING",

    val card3Type: String = "METRIC",
    val card3MetricType: String = "HRV",
    val card3Value: Double = 0.0,
    val card3Status: String = "CALIBRATING",

    val card4Type: String = "STEPS",
    val card4CurrentSteps: Long = 0L,
    val card4GoalSteps: Long = 10000L,

    // Common
    val lastUpdateMs: Long = 0L,
    val error: String? = null,
) {
    companion object {
        const val CARD_TYPE_METRIC = "METRIC"
        const val CARD_TYPE_STEPS = "STEPS"
        const val CARD_TYPE_SCORE = "SCORE"

        fun createStringKey(widgetId: Int, suffix: String) =
            stringPreferencesKey("widget_large_${widgetId}_${suffix}")

        fun createDoubleKey(widgetId: Int, suffix: String) =
            doublePreferencesKey("widget_large_${widgetId}_${suffix}")

        fun createLongKey(widgetId: Int, suffix: String) =
            longPreferencesKey("widget_large_${widgetId}_${suffix}")
    }
}
