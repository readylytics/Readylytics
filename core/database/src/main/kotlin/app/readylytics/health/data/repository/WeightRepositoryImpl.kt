package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeightRepositoryImpl
    @Inject
    constructor(
        private val dao: WeightRecordDao,
    ) : WeightRepository {
        override suspend fun getByDateRange(
            fromMs: Long,
            toMs: Long,
        ): List<WeightRecordEntity> = dao.getByTimeRange(fromMs, toMs)

        override fun observeByDateRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<List<WeightRecordEntity>> = dao.observeByTimeRange(fromMs, toMs)

        override suspend fun getLatest(): WeightRecordEntity? = dao.getLatest()

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): WeightRecordEntity? = dao.getLatestByDate(dayStartMs, dayEndMs)

        override suspend fun getPrevious(beforeMs: Long): WeightRecordEntity? = dao.getPrevious(beforeMs)
    }
