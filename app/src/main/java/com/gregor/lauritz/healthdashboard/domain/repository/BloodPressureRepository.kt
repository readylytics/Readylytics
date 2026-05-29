package com.gregor.lauritz.healthdashboard.domain.repository

import com.gregor.lauritz.healthdashboard.data.local.entity.BloodPressureRecordEntity
import kotlinx.coroutines.flow.Flow

interface BloodPressureRepository {
    suspend fun getByDateRange(
        fromMs: Long,
        toMs: Long,
    ): List<BloodPressureRecordEntity>

    fun observeByDateRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<List<BloodPressureRecordEntity>>

    suspend fun getLatest(): BloodPressureRecordEntity?

    suspend fun getLatestByDate(
        dayStartMs: Long,
        dayEndMs: Long,
    ): BloodPressureRecordEntity?
}
