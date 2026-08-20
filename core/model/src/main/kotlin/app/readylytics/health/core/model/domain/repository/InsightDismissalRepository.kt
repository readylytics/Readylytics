package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.InsightType
import kotlinx.coroutines.flow.Flow

interface InsightDismissalRepository {
    suspend fun dismiss(
        dateMidnightMs: Long,
        type: InsightType,
    )

    suspend fun restoreAllForDate(dateMidnightMs: Long)

    fun observeForDate(dateMidnightMs: Long): Flow<Set<InsightType>>
}
