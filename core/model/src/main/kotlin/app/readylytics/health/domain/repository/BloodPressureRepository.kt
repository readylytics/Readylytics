package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.BloodPressureRecord
import kotlinx.coroutines.flow.Flow

interface BloodPressureRepository {
    suspend fun getByDateRange(fromMs: Long, toMs: Long): List<BloodPressureRecord>
    
    suspend fun getByDateRangePaged(
        fromMs: Long,
        toMs: Long,
        limit: Int,
        offset: Int,
    ): List<BloodPressureRecord>

    suspend fun countByDateRange(fromMs: Long, toMs: Long): Int

    fun observeByDateRange(fromMs: Long, toMs: Long): Flow<List<BloodPressureRecord>>
    suspend fun getLatest(): BloodPressureRecord?
    suspend fun getLatestByDate(dayStartMs: Long, dayEndMs: Long): BloodPressureRecord?
}
