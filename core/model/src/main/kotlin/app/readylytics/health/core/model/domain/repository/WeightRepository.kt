package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.WeightRecord
import kotlinx.coroutines.flow.Flow

interface WeightRepository {
    suspend fun getByDateRange(fromMs: Long, toMs: Long): List<WeightRecord>

    suspend fun getByDateRangePaged(
        fromMs: Long,
        toMs: Long,
        limit: Int,
        offset: Int,
    ): List<WeightRecord>

    suspend fun countByDateRange(fromMs: Long, toMs: Long): Int

    fun observeByDateRange(fromMs: Long, toMs: Long): Flow<List<WeightRecord>>
    suspend fun getLatest(): WeightRecord?
    suspend fun getLatestByDate(dayStartMs: Long, dayEndMs: Long): WeightRecord?
    suspend fun getPrevious(beforeMs: Long): WeightRecord?
}
