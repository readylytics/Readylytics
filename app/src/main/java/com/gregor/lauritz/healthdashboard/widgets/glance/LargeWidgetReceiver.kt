package com.gregor.lauritz.healthdashboard.widgets.glance

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class LargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = LargeWidget()
}
