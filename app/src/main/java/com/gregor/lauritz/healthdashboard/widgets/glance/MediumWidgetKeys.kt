package com.gregor.lauritz.healthdashboard.widgets.glance

import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object MediumWidgetKeys {
    // Mode
    fun mode(widgetId: Int) = stringPreferencesKey("widget_medium_${widgetId}_mode")

    // Dual metric mode
    fun metric1Type(widgetId: Int) = stringPreferencesKey("widget_medium_${widgetId}_metric1_type")

    fun metric1Value(widgetId: Int) = doublePreferencesKey("widget_medium_${widgetId}_metric1_value")

    fun metric1Status(widgetId: Int) = stringPreferencesKey("widget_medium_${widgetId}_metric1_status")

    fun metric2Type(widgetId: Int) = stringPreferencesKey("widget_medium_${widgetId}_metric2_type")

    fun metric2Value(widgetId: Int) = doublePreferencesKey("widget_medium_${widgetId}_metric2_value")

    fun metric2Status(widgetId: Int) = stringPreferencesKey("widget_medium_${widgetId}_metric2_status")

    // Steps progress mode
    fun currentSteps(widgetId: Int) = longPreferencesKey("widget_medium_${widgetId}_current_steps")

    fun goalSteps(widgetId: Int) = longPreferencesKey("widget_medium_${widgetId}_goal_steps")

    // Common
    fun lastUpdate(widgetId: Int) = longPreferencesKey("widget_medium_${widgetId}_last_update")

    fun error(widgetId: Int) = stringPreferencesKey("widget_medium_${widgetId}_error")
}
