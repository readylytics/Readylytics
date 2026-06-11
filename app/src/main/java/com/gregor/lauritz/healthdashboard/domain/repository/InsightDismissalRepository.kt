package com.gregor.lauritz.healthdashboard.domain.repository

import com.gregor.lauritz.healthdashboard.domain.model.InsightType
import kotlinx.coroutines.flow.Flow

interface InsightDismissalRepository {
    suspend fun dismiss(
        dateMidnightMs: Long,
        type: InsightType,
    )

    suspend fun restoreAllForDate(dateMidnightMs: Long)

    fun observeForDate(dateMidnightMs: Long): Flow<Set<InsightType>>
}
