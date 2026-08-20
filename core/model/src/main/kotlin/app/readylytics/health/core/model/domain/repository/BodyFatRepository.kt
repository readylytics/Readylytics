package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.domain.model.BodyFatRecord
import kotlinx.coroutines.flow.Flow

interface BodyFatRepository {
    suspend fun getByDateRange(fromMs: Long, toMs: Long): List<BodyFatRecord>

    suspend fun getByDateRangePaged(
        fromMs: Long,
        toMs: Long,
        limit: Int,
        offset: Int,
    ): List<BodyFatRecord>

    suspend fun countByDateRange(fromMs: Long, toMs: Long): Int

    fun observeByDateRange(fromMs: Long, toMs: Long): Flow<List<BodyFatRecord>>
    suspend fun getLatest(): BodyFatRecord?
    suspend fun getLatestByDate(dayStartMs: Long, dayEndMs: Long): BodyFatRecord?
    suspend fun getPrevious(beforeMs: Long): BodyFatRecord?
}
