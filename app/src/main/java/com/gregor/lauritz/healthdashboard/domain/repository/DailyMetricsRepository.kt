package com.gregor.lauritz.healthdashboard.domain.repository

import com.gregor.lauritz.healthdashboard.domain.model.DailyMetrics
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Single consumer-facing surface for rounding-safe daily metrics.
 *
 * Returns the canonical [DailyMetrics] projection (raw + pre-rounded display values)
 * for a date, sourced from the self-contained `daily_summaries` row and current
 * preferences. Reactive `observe*` variants drive StateFlow UI; the suspend variant
 * snapshots preferences via `first()`.
 */
interface DailyMetricsRepository {
    suspend fun getDailyMetrics(date: LocalDate): DailyMetrics?

    fun observeByDate(date: LocalDate): Flow<DailyMetrics?>

    fun observeSince(fromMs: Long): Flow<List<DailyMetrics>>
}
