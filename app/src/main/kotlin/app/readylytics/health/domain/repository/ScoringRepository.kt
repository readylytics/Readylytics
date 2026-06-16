package app.readylytics.health.domain.repository

import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.domain.model.ReadinessResult
import java.time.LocalDate

interface ScoringRepository {
    suspend fun computeAndPersistDailySummary(targetDate: LocalDate = LocalDate.now())

    suspend fun computeDailySummary(targetDate: LocalDate = LocalDate.now()): DailySummaryEntity

    suspend fun toReadinessResult(summary: DailySummaryEntity): ReadinessResult
}
