package com.gregor.lauritz.healthdashboard.widgets.workers

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val TAG = "WidgetUpdateWorker"

/**
 * Periodic worker that updates all widgets daily at 6 AM.
 * Ensures widgets stay fresh even if app hasn't synced recently.
 * Scheduled to persist across device reboots via WorkManager.
 */
@HiltWorker
class WidgetUpdateWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val widgetDataRepository: WidgetDataRepository,
        private val configRepository: WidgetConfigurationRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            var successCount = 0
            var failureCount = 0

            return try {
                val glanceManager = GlanceAppWidgetManager(applicationContext)

                // Update small widgets
                try {
                    val smallWidgetIds = glanceManager.getGlanceIds(SmallWidget::class.java)
                    smallWidgetIds.forEach { glanceId ->
                        val widgetId = glanceManager.getAppWidgetId(glanceId)
                        try {
                            SmallWidgetUpdater.updateSmallWidget(
                                applicationContext,
                                widgetId,
                                widgetDataRepository,
                                configRepository,
                            )
                            successCount++
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update small widget $widgetId", e)
                            failureCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update small widgets", e)
                    failureCount++
                }

                // Update medium widgets
                try {
                    val mediumWidgetIds = glanceManager.getGlanceIds(MediumWidget::class.java)
                    mediumWidgetIds.forEach { glanceId ->
                        val widgetId = glanceManager.getAppWidgetId(glanceId)
                        try {
                            MediumWidgetUpdater.updateMediumWidget(
                                applicationContext,
                                widgetId,
                                widgetDataRepository,
                                configRepository,
                            )
                            successCount++
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update medium widget $widgetId", e)
                            failureCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update medium widgets", e)
                    failureCount++
                }

                // Update large widgets
                try {
                    val largeWidgetIds = glanceManager.getGlanceIds(LargeWidget::class.java)
                    largeWidgetIds.forEach { glanceId ->
                        val widgetId = glanceManager.getAppWidgetId(glanceId)
                        try {
                            LargeWidgetUpdater.updateLargeWidget(
                                applicationContext,
                                widgetId,
                                widgetDataRepository,
                                configRepository,
                            )
                            successCount++
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update large widget $widgetId", e)
                            failureCount++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update large widgets", e)
                    failureCount++
                }

                // Trigger batch updates once at the end
                SmallWidget().updateAll(applicationContext)
                MediumWidget().updateAll(applicationContext)
                LargeWidget().updateAll(applicationContext)

                Log.i(TAG, "Widget update completed: $successCount success, $failureCount failures")
                if (failureCount > 0 && successCount == 0) {
                    Result.retry()
                } else {
                    Result.success()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Widget update worker failed", e)
                Result.retry()
            }
        }

        companion object {
            private const val WORKER_NAME = "widget_update_worker"

            fun schedule(context: Context) {
                val updateRequest =
                    PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                        1,
                        TimeUnit.DAYS,
                    ).setInitialDelay(calculateDelayTo6AM(), TimeUnit.MINUTES)
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
                val calendar =
                    java.util.Calendar.getInstance().apply {
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
