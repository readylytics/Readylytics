package app.readylytics.health.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.database.data.mapper.WeightRecordMapper
import app.readylytics.health.domain.model.WeightRecord
import app.readylytics.health.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeightRepositoryImpl
    @Inject
    constructor(private val dao: WeightRecordDao) : WeightRepository {
        override suspend fun getByDateRange(fromMs: Long, toMs: Long): List<WeightRecord> =
            dao.getByTimeRange(fromMs, toMs).map(WeightRecordMapper::toDomain)

        override suspend fun getByDateRangePaged(
            fromMs: Long,
            toMs: Long,
            limit: Int,
            offset: Int,
        ): List<WeightRecord> =
            dao.getPagedByTimeRange(fromMs, toMs, limit, offset).map(WeightRecordMapper::toDomain)

        override suspend fun countByDateRange(fromMs: Long, toMs: Long): Int =
            dao.countByTimeRange(fromMs, toMs)

        override fun observeByDateRange(fromMs: Long, toMs: Long): Flow<List<WeightRecord>> =
            dao.observeByTimeRange(fromMs, toMs).map { entities -> entities.map(WeightRecordMapper::toDomain) }

        override suspend fun getLatest(): WeightRecord? = dao.getLatest()?.let(WeightRecordMapper::toDomain)

        override suspend fun getLatestByDate(dayStartMs: Long, dayEndMs: Long): WeightRecord? =
            dao.getLatestByDate(dayStartMs, dayEndMs)?.let(WeightRecordMapper::toDomain)

        override suspend fun getPrevious(beforeMs: Long): WeightRecord? =
            dao.getPrevious(beforeMs)?.let(WeightRecordMapper::toDomain)
    }
