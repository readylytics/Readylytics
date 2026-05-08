package com.gregor.lauritz.healthdashboard.widgets.receiver

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetDataRepository
import com.gregor.lauritz.healthdashboard.widgets.glance.LargeWidget
import com.gregor.lauritz.healthdashboard.widgets.glance.LargeWidgetUpdater
import com.gregor.lauritz.healthdashboard.widgets.glance.MediumWidget
import com.gregor.lauritz.healthdashboard.widgets.glance.MediumWidgetUpdater
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidget
import com.gregor.lauritz.healthdashboard.widgets.glance.SmallWidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WidgetUpdateBroadcastReceiver"

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

        val result = goAsync()
        MainScope().launch {
            try {
                val glanceManager = GlanceAppWidgetManager(context)

                // Update small widgets
                try {
                    val smallWidgetIds = glanceManager.getGlanceIds(SmallWidget::class.java)
                    smallWidgetIds.forEach { glanceId ->
                        val widgetId = glanceId.hashCode()
                        try {
                            SmallWidgetUpdater.updateSmallWidget(
                                context,
                                widgetId,
                                widgetDataRepository,
                                configRepository,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update small widget $widgetId", e)
                        }
                    }
                    glanceManager.updateAll(SmallWidget::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update small widgets", e)
                }

                // Update medium widgets
                try {
                    val mediumWidgetIds = glanceManager.getGlanceIds(MediumWidget::class.java)
                    mediumWidgetIds.forEach { glanceId ->
                        val widgetId = glanceId.hashCode()
                        try {
                            MediumWidgetUpdater.updateMediumWidget(
                                context,
                                widgetId,
                                widgetDataRepository,
                                configRepository,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update medium widget $widgetId", e)
                        }
                    }
                    glanceManager.updateAll(MediumWidget::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update medium widgets", e)
                }

                // Update large widgets
                try {
                    val largeWidgetIds = glanceManager.getGlanceIds(LargeWidget::class.java)
                    largeWidgetIds.forEach { glanceId ->
                        val widgetId = glanceId.hashCode()
                        try {
                            LargeWidgetUpdater.updateLargeWidget(
                                context,
                                widgetId,
                                widgetDataRepository,
                                configRepository,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update large widget $widgetId", e)
                        }
                    }
                    glanceManager.updateAll(LargeWidget::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update large widgets", e)
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_SYNC_COMPLETE = "com.gregor.lauritz.healthdashboard.SYNC_COMPLETE"
    }
}

