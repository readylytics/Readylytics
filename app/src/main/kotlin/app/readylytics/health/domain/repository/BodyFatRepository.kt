package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.BodyFatRecordEntity
import kotlinx.coroutines.flow.Flow

interface BodyFatRepository {
    suspend fun getByDateRange(
        fromMs: Long,
        toMs: Long,
    ): List<BodyFatRecordEntity>

    fun observeByDateRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<List<BodyFatRecordEntity>>

    suspend fun getLatest(): BodyFatRecordEntity?

    suspend fun getLatestByDate(
        dayStartMs: Long,
        dayEndMs: Long,
    ): BodyFatRecordEntity?

    suspend fun getPrevious(beforeMs: Long): BodyFatRecordEntity?
}
