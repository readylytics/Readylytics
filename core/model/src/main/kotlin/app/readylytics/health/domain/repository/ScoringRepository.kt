package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.ReadinessResult
import java.time.LocalDate

interface ScoringRepository {
    suspend fun computeAndPersistDailySummary(
        targetDate: LocalDate = LocalDate.now(),
        steps: Long? = null,
    )

    suspend fun computeDailySummary(targetDate: LocalDate = LocalDate.now()): DailySummary

    suspend fun persist(summary: DailySummary)

    suspend fun toReadinessResult(summary: DailySummary): ReadinessResult
}
