package com.gregor.lauritz.healthdashboard.widgets.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver that listens for sync completion events and triggers widget updates.
 *
 * Registered to listen for custom broadcast action: "com.gregor.lauritz.healthdashboard.SYNC_COMPLETE"
 */
@AndroidEntryPoint
class WidgetUpdateBroadcastReceiver : BroadcastReceiver() {
    @Inject
    lateinit var glanceAppWidgetManager: GlanceAppWidgetManager

    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent?.action != ACTION_SYNC_COMPLETE) return

        context ?: return

        // Update all widgets when sync completes
        MainScope().launch {
            glanceAppWidgetManager.updateAll(ReadylyticsAppWidget::class.java)
        }
    }

    companion object {
        const val ACTION_SYNC_COMPLETE = "com.gregor.lauritz.healthdashboard.SYNC_COMPLETE"
    }
}
