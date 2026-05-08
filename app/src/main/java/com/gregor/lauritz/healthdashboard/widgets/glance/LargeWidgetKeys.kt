package com.gregor.lauritz.healthdashboard.widgets.glance

import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object LargeWidgetKeys {
    // Card 1
    fun card1Type(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card1_type")
    fun card1Metric(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card1_metric")
    fun card1Value(widgetId: Int) = doublePreferencesKey("widget_large_${widgetId}_card1_value")
    fun card1Status(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card1_status")

    // Card 2
    fun card2Type(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card2_type")
    fun card2Metric(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card2_metric")
    fun card2Value(widgetId: Int) = doublePreferencesKey("widget_large_${widgetId}_card2_value")
    fun card2Status(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card2_status")

    // Card 3
    fun card3Type(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card3_type")
    fun card3Metric(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card3_metric")
    fun card3Value(widgetId: Int) = doublePreferencesKey("widget_large_${widgetId}_card3_value")
    fun card3Status(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card3_status")

    // Card 4
    fun card4Type(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card4_type")
    fun card4Metric(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card4_metric")
    fun card4Value(widgetId: Int) = doublePreferencesKey("widget_large_${widgetId}_card4_value")
    fun card4Status(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_card4_status")

    // Common
    fun lastUpdate(widgetId: Int) = longPreferencesKey("widget_large_${widgetId}_last_update")
    fun error(widgetId: Int) = stringPreferencesKey("widget_large_${widgetId}_error")
}
