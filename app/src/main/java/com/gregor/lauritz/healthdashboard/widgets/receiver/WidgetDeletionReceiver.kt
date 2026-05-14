package com.gregor.lauritz.healthdashboard.widgets.receiver

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WidgetDeletionReceiver"

/**
 * Broadcast receiver that handles widget deletion events.
 * Cleans up configuration data from DataStore when users delete widgets.
 */
@AndroidEntryPoint
class WidgetDeletionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var configRepository: WidgetConfigurationRepository

    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent?.action != AppWidgetManager.ACTION_APPWIDGET_DELETED) return
        context ?: return

        val widgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )

        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val result = goAsync()
            MainScope().launch {
                try {
                    configRepository.deleteWidgetConfig(widgetId, WidgetType.SMALL)
                    configRepository.deleteWidgetConfig(widgetId, WidgetType.MEDIUM)
                    configRepository.deleteWidgetConfig(widgetId, WidgetType.LARGE)
                    Log.d(TAG, "Deleted config for widget $widgetId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete widget config for $widgetId", e)
                } finally {
                    result.finish()
                }
            }
        }
    }
}
