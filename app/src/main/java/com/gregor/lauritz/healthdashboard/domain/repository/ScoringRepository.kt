package com.gregor.lauritz.healthdashboard.domain.repository

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult
import java.time.LocalDate

interface ScoringRepository {
    suspend fun computeAndPersistDailySummary(targetDate: LocalDate = LocalDate.now())
    suspend fun computeDailySummary(targetDate: LocalDate = LocalDate.now()): DailySummaryEntity
    fun toReadinessResult(summary: DailySummaryEntity): ReadinessResult
}
