package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.WeightRecord
import kotlinx.coroutines.flow.Flow

interface WeightRepository {
    suspend fun getByDateRange(fromMs: Long, toMs: Long): List<WeightRecord>
    fun observeByDateRange(fromMs: Long, toMs: Long): Flow<List<WeightRecord>>
    suspend fun getLatest(): WeightRecord?
    suspend fun getLatestByDate(dayStartMs: Long, dayEndMs: Long): WeightRecord?
    suspend fun getPrevious(beforeMs: Long): WeightRecord?
}
