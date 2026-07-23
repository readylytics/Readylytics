package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.mapper.WeightRecordMapper
import app.readylytics.health.domain.model.WeightRecord
import app.readylytics.health.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        ): List<WeightRecord> = dao.getByTimeRange(fromMs, toMs).map(WeightRecordMapper::toDomain)

        override fun observeByDateRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<List<WeightRecord>> =
            dao.observeByTimeRange(fromMs, toMs).map { entities -> entities.map(WeightRecordMapper::toDomain) }

        override suspend fun getLatest(): WeightRecord? = dao.getLatest()?.let(WeightRecordMapper::toDomain)

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): WeightRecord? = dao.getLatestByDate(dayStartMs, dayEndMs)?.let(WeightRecordMapper::toDomain)

        override suspend fun getPrevious(beforeMs: Long): WeightRecord? =
            dao.getPrevious(beforeMs)?.let(WeightRecordMapper::toDomain)
    }
