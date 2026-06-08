package com.gregor.lauritz.healthdashboard.workers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.gregor.lauritz.healthdashboard.R

/**
 * Notification channel + builder for the foreground historical-resync worker
 * ([HealthResyncWorker]). minSdk is well above API 26, so the channel is created unconditionally.
 */
object SyncNotifications {
    const val CHANNEL_ID = "resync_progress"
    const val NOTIFICATION_ID = 4011

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.resync_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.resync_channel_description)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildProgressNotification(
        context: Context,
        current: Int,
        total: Int,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.resync_notification_title))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (total > 0) {
            builder
                .setContentText(context.getString(R.string.recalculating_progress, current, total))
                .setProgress(total, current, false)
        } else {
            builder
                .setContentText(context.getString(R.string.resync_notification_preparing))
                .setProgress(0, 0, true)
        }
        return builder.build()
    }
}
