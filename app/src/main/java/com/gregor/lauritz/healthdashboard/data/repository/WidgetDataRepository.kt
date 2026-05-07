package com.gregor.lauritz.healthdashboard.data.repository

import androidx.room.Transaction
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for providing widget-optimized metric data.
 * Wraps existing DAOs to expose current day's metrics efficiently.
 */
@Singleton
class WidgetDataRepository @Inject constructor(
    private val dailySummaryDao: DailySummaryDao,
    private val sleepSessionDao: SleepSessionDao,
) {
    /**
     * Observes the latest daily summary (most recent day with data).
     * Used by widgets to display current metrics.
     */
    fun observeLatestSummary(): Flow<DailySummary?> =
        dailySummaryDao.observeLatest().map { entity ->
            entity?.let { DailySummary.from(it) }
        }

    /**
     * Observes the daily summary for a specific date (midnight ms).
     * Used by widgets displaying a specific date.
     */
    fun observeSummaryByDate(dateMidnightMs: Long): Flow<DailySummary?> =
        dailySummaryDao.observeByDate(dateMidnightMs).map { entity ->
            entity?.let { DailySummary.from(it) }
        }

    /**
     * Get latest summary synchronously (for widget initial load).
     */
    suspend fun getLatestSummaryAsync(): DailySummary? =
        dailySummaryDao.getLatestAsync()?.let { DailySummary.from(it) }

    /**
     * Get summary for specific date synchronously.
     */
    suspend fun getSummaryByDateAsync(dateMidnightMs: Long): DailySummary? =
        dailySummaryDao.getByDateAsync(dateMidnightMs)?.let { DailySummary.from(it) }

    /**
     * Observes summaries for the past N days (for trend widgets).
     */
    fun observeSince(fromMidnightMs: Long): Flow<List<DailySummary>> =
        dailySummaryDao.observeSince(fromMidnightMs).map { entities ->
            entities.map { DailySummary.from(it) }
        }
}
