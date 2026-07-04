package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.BloodPressureRecordEntity
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
