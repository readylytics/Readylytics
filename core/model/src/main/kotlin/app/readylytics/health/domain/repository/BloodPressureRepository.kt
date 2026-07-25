package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.BloodPressureRecord
import kotlinx.coroutines.flow.Flow

interface BloodPressureRepository {
    suspend fun getByDateRange(fromMs: Long, toMs: Long): List<BloodPressureRecord>
    fun observeByDateRange(fromMs: Long, toMs: Long): Flow<List<BloodPressureRecord>>
    suspend fun getLatest(): BloodPressureRecord?
    suspend fun getLatestByDate(dayStartMs: Long, dayEndMs: Long): BloodPressureRecord?
}
