package com.gregor.lauritz.healthdashboard.domain.repository

import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import kotlinx.coroutines.flow.Flow

interface DailySummaryRepository {
    fun observeLatest(): Flow<DailySummary?>

    fun observeSince(fromMs: Long): Flow<List<DailySummary>>

    fun observeByDate(dateMidnightMs: Long): Flow<DailySummary?>

    suspend fun getByDate(dateMidnightMs: Long): DailySummary?

    suspend fun getSince(fromMs: Long): List<DailySummary>

    fun observeFirstSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<SleepSessionData?>
}
