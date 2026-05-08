package com.gregor.lauritz.healthdashboard.widgets.workers

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that updates all widgets daily at 6 AM.
 * Ensures widgets stay fresh even if app hasn't synced recently.
 * Scheduled to persist across device reboots via WorkManager.
 */
@AndroidEntryPoint
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @Inject
    lateinit var widgetDataRepository: WidgetDataRepository

    @Inject
    lateinit var configRepository: WidgetConfigurationRepository

    override suspend fun doWork(): Result {
        return try {
            val glanceManager = GlanceAppWidgetManager(applicationContext)

            // Update small widgets
            try {
                val smallWidgetIds = glanceManager.getGlanceIds(SmallWidget::class.java)
                smallWidgetIds.forEach { glanceId ->
                    val widgetId = glanceId.hashCode()
                    try {
                        SmallWidgetUpdater.updateSmallWidget(
                            applicationContext,
                            widgetId,
                            widgetDataRepository,
                            configRepository,
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Update medium widgets
            try {
                val mediumWidgetIds = glanceManager.getGlanceIds(MediumWidget::class.java)
                mediumWidgetIds.forEach { glanceId ->
                    val widgetId = glanceId.hashCode()
                    try {
                        MediumWidgetUpdater.updateMediumWidget(
                            applicationContext,
                            widgetId,
                            widgetDataRepository,
                            configRepository,
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Update large widgets
            try {
                val largeWidgetIds = glanceManager.getGlanceIds(LargeWidget::class.java)
                largeWidgetIds.forEach { glanceId ->
                    val widgetId = glanceId.hashCode()
                    try {
                        LargeWidgetUpdater.updateLargeWidget(
                            applicationContext,
                            widgetId,
                            widgetDataRepository,
                            configRepository,
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    companion object {
        private const val WORKER_NAME = "widget_update_worker"

        fun schedule(context: Context) {
            val updateRequest = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                1,
                TimeUnit.DAYS,
            )
                .setInitialDelay(calculateDelayTo6AM(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORKER_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest,
            )
        }

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
                if (timeInMillis <= now) {
                    add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
            }
            return (calendar.timeInMillis - now) / (1000 * 60)
        }
    }
}
