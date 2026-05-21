package com.gregor.lauritz.healthdashboard.domain.repository

import com.gregor.lauritz.healthdashboard.data.local.entity.WeightRecordEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface WeightRepository {
    suspend fun getByDateRange(
        fromMs: Long,
        toMs: Long,
    ): List<WeightRecordEntity>

    fun observeByDateRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<List<WeightRecordEntity>>

    suspend fun getLatest(): WeightRecordEntity?

    suspend fun getLatestByDate(
        dayStartMs: Long,
        dayEndMs: Long,
    ): WeightRecordEntity?
}
