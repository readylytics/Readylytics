package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.BodyFatRecord
import kotlinx.coroutines.flow.Flow

interface BodyFatRepository {
    suspend fun getByDateRange(fromMs: Long, toMs: Long): List<BodyFatRecord>
    fun observeByDateRange(fromMs: Long, toMs: Long): Flow<List<BodyFatRecord>>
    suspend fun getLatest(): BodyFatRecord?
    suspend fun getLatestByDate(dayStartMs: Long, dayEndMs: Long): BodyFatRecord?
    suspend fun getPrevious(beforeMs: Long): BodyFatRecord?
}
