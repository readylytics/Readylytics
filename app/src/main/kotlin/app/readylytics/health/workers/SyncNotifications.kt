package app.readylytics.health.workers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.readylytics.health.R
import app.readylytics.health.core.model.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.core.model.domain.migration.fraction
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.fraction
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * Notification channel + builder for the foreground historical-resync worker
 * ([HealthResyncWorker]). minSdk is well above API 26, so the channel is created unconditionally.
 */
object SyncNotifications {
    const val CHANNEL_ID = "resync_progress"
    const val NOTIFICATION_ID = 4011

    const val BACKGROUND_SYNC_CHANNEL_ID = "background_sync"
    const val BACKGROUND_SYNC_NOTIFICATION_ID = 4012

    const val DATABASE_MIGRATION_CHANNEL_ID = "database_migration"
    const val DATABASE_MIGRATION_NOTIFICATION_ID = 4013

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

    fun ensureBackgroundSyncChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(BACKGROUND_SYNC_CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(
                    BACKGROUND_SYNC_CHANNEL_ID,
                    context.getString(R.string.background_sync_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.background_sync_channel_description)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
        }
    }

    fun ensureDatabaseMigrationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DATABASE_MIGRATION_CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(
                    DATABASE_MIGRATION_CHANNEL_ID,
                    context.getString(R.string.database_migration_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.database_migration_channel_description)
                    setShowBadge(false)
                }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildDatabaseMigrationNotification(
        context: Context,
        progress: DatabaseMigrationProgress,
    ): Notification {
        val determinate = progress.totalRows > 0L
        val text =
            if (determinate) {
                context.resources.getQuantityString(
                    R.plurals.database_migration_progress,
                    progress.totalRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    progress.copiedRows,
                    progress.totalRows,
                )
            } else {
                context.getString(R.string.database_migration_description)
            }
        return NotificationCompat
            .Builder(context, DATABASE_MIGRATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.database_migration_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                100,
                (progress.fraction() * 100).roundToInt(),
                !determinate,
            ).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun buildBackgroundSyncNotification(context: Context): Notification =
        NotificationCompat
            .Builder(context, BACKGROUND_SYNC_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.background_sync_notification_title))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun buildProgressNotification(
        context: Context,
        phase: ResyncPhase?,
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

        val text =
            when (phase) {
                null -> context.getString(CoreUiR.string.resync_notification_preparing)
                ResyncPhase.INGEST -> context.getString(CoreUiR.string.resync_phase_ingest, current, total)
                ResyncPhase.PRUNE -> context.getString(CoreUiR.string.resync_phase_prune)
                ResyncPhase.RECONCILE -> context.getString(CoreUiR.string.resync_phase_reconcile)
                ResyncPhase.RECOMPUTE -> context.getString(CoreUiR.string.recalculating_progress, current, total)
            }
        val fraction = phase?.let { RecalcProgress(it, current, total).fraction() } ?: 0f
        builder
            .setContentText(text)
            .setProgress(100, (fraction * 100).roundToInt(), false)
        return builder.build()
    }
}
