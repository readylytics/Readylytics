package com.gregor.lauritz.healthdashboard.widgets.glance

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SmallWidget()
}
