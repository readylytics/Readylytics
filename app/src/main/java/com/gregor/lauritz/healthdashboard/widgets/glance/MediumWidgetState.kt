package com.gregor.lauritz.healthdashboard.widgets.glance

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey

/**
 * Data model for medium widget (1x4).
 * Supports two modes:
 * 1. DUAL_METRIC: Two metrics displayed side-by-side
 * 2. STEPS_PROGRESS: Horizontal progress bar for steps vs goal
 */
data class MediumWidgetData(
    val mode: String = "DUAL_METRIC",
    // Dual metric mode
    val metric1Type: String = "HRV",
    val metric1Value: Double = 0.0,
    val metric1Status: String = "CALIBRATING",
    val metric2Type: String = "RHR",
    val metric2Value: Double = 0.0,
    val metric2Status: String = "CALIBRATING",
    // Steps progress mode
    val currentSteps: Long = 0L,
    val goalSteps: Long = 10000L,
    // Common
    val lastUpdateMs: Long = 0L,
    val error: String? = null,
) {
    companion object {
        // Keys for DataStore persistence
        const val MODE_DUAL_METRIC = "DUAL_METRIC"
        const val MODE_STEPS_PROGRESS = "STEPS_PROGRESS"

        fun createModeKey(widgetId: Int, suffix: String) =
            stringPreferencesKey("widget_medium_${widgetId}_${suffix}")

        fun createDoubleKey(widgetId: Int, suffix: String) =
            doublePreferencesKey("widget_medium_${widgetId}_${suffix}")

        fun createLongKey(widgetId: Int, suffix: String) =
            longPreferencesKey("widget_medium_${widgetId}_${suffix}")
    }
}
