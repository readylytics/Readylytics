package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.WeightRecordEntity
import kotlinx.coroutines.flow.Flow

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

    suspend fun getPrevious(beforeMs: Long): WeightRecordEntity?
}
