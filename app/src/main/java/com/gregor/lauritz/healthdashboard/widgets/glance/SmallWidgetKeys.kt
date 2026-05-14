package com.gregor.lauritz.healthdashboard.widgets.glance

import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object SmallWidgetKeys {
    fun metricType(widgetId: Int) = stringPreferencesKey("widget_small_${widgetId}_metric_type")

    fun value(widgetId: Int) = doublePreferencesKey("widget_small_${widgetId}_value")

    fun status(widgetId: Int) = stringPreferencesKey("widget_small_${widgetId}_status")

    fun trend(widgetId: Int) = doublePreferencesKey("widget_small_${widgetId}_trend")

    fun lastUpdate(widgetId: Int) = longPreferencesKey("widget_small_${widgetId}_last_update")

    fun error(widgetId: Int) = stringPreferencesKey("widget_small_${widgetId}_error")
}
