package com.gregor.lauritz.healthdashboard.widgets.workers

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.widgets.receiver.ReadylyticsAppWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Periodic worker that updates all widgets daily.
 * Ensures widgets stay fresh even if app hasn't synced recently.
 */
class WidgetUpdateWorker(
    context: Context,
    params: androidx.work.WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val glanceAppWidgetManager = GlanceAppWidgetManager(applicationContext)
            glanceAppWidgetManager.updateAll(ReadylyticsAppWidget::class.java)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORKER_NAME = "widget_update_worker"

        /**
         * Schedule periodic widget update worker (daily at 6 AM).
         */
        fun schedule(context: Context) {
            val updateRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                1,
                TimeUnit.DAYS,
            )
                .setInitialDelay(
                    calculateDelayTo6AM(),
                    TimeUnit.MINUTES,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORKER_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest,
            )
        }

        /**
         * Cancel periodic widget update.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORKER_NAME)
        }

        private fun calculateDelayTo6AM(): Long {
            val now = System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 6)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)

                // If 6 AM has passed today, schedule for tomorrow
                if (timeInMillis <= now) {
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
            }

            return (calendar.timeInMillis - now) / (1000 * 60) // Convert to minutes
        }
    }
}
