package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.mapper.BodyFatRecordMapper
import app.readylytics.health.domain.model.BodyFatRecord
import app.readylytics.health.domain.repository.BodyFatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BodyFatRepositoryImpl
    @Inject
    constructor(
        private val dao: BodyFatRecordDao,
    ) : BodyFatRepository {
        override suspend fun getByDateRange(
            fromMs: Long,
            toMs: Long,
        ): List<BodyFatRecord> = dao.getByTimeRange(fromMs, toMs).map(BodyFatRecordMapper::toDomain)

        override fun observeByDateRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<List<BodyFatRecord>> =
            dao.observeByTimeRange(fromMs, toMs).map { entities -> entities.map(BodyFatRecordMapper::toDomain) }

        override suspend fun getLatest(): BodyFatRecord? = dao.getLatest()?.let(BodyFatRecordMapper::toDomain)

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): BodyFatRecord? = dao.getLatestByDate(dayStartMs, dayEndMs)?.let(BodyFatRecordMapper::toDomain)

        override suspend fun getPrevious(beforeMs: Long): BodyFatRecord? =
            dao.getPrevious(beforeMs)?.let(BodyFatRecordMapper::toDomain)
    }
