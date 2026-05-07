package com.gregor.lauritz.healthdashboard.widgets.receiver

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Base receiver for all Readylytics widgets.
 * Routes widget creation to appropriate widget class based on provider configuration.
 */
class ReadylyticsAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadylyticsAppWidget()
}

/**
 * Placeholder widget that will be replaced with actual implementations.
 * This is the entry point for all widget instances (small, medium, large).
 */
class ReadylyticsAppWidget : GlanceAppWidget() {
    // This will be replaced with actual widget implementation in later phases
}
