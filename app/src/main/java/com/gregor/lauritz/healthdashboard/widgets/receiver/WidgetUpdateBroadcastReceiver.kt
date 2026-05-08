package com.gregor.lauritz.healthdashboard.widgets.receiver

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidget
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Broadcast receiver that listens for sync completion events and triggers widget updates.
 *
 * Registered to listen for custom broadcast action: "com.gregor.lauritz.healthdashboard.SYNC_COMPLETE"
 * Called from ForegroundSyncController when app syncs with Health Connect.
 */
@AndroidEntryPoint
class WidgetUpdateBroadcastReceiver : BroadcastReceiver() {
    @Inject
    lateinit var widgetDataRepository: WidgetDataRepository

    @Inject
    lateinit var configRepository: WidgetConfigurationRepository

    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent?.action != ACTION_SYNC_COMPLETE) return
        context ?: return

        // Update all widgets when sync completes
        MainScope().launch {
            val glanceManager = GlanceAppWidgetManager(context)

            // Get all widget IDs for the app
            val widgetIds = glanceManager.getGlanceIds(SmallWidget::class.java)

            // Update each widget
            widgetIds.forEach { glanceId ->
                val widgetId = glanceId.hashCode()
                try {
                    SmallWidgetUpdater.updateSmallWidget(
                        context,
                        widgetId,
                        widgetDataRepository,
                        configRepository,
                    )
                } catch (e: Exception) {
                    // Log but don't crash
                    e.printStackTrace()
                }
            }

            // Trigger recomposition
            glanceManager.updateAll(SmallWidget::class.java)
        }
    }

    companion object {
        const val ACTION_SYNC_COMPLETE = "com.gregor.lauritz.healthdashboard.SYNC_COMPLETE"
    }
}

