package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.mapper.BloodPressureRecordMapper
import app.readylytics.health.domain.model.BloodPressureRecord
import app.readylytics.health.domain.repository.BloodPressureRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BloodPressureRepositoryImpl
    @Inject
    constructor(
        private val dao: BloodPressureRecordDao,
    ) : BloodPressureRepository {
        override suspend fun getByDateRange(
            fromMs: Long,
            toMs: Long,
        ): List<BloodPressureRecord> = dao.getByTimeRange(fromMs, toMs).map(BloodPressureRecordMapper::toDomain)

        override fun observeByDateRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<List<BloodPressureRecord>> =
            dao.observeByTimeRange(fromMs, toMs).map { entities -> entities.map(BloodPressureRecordMapper::toDomain) }

        override suspend fun getLatest(): BloodPressureRecord? =
            dao.getLatest()?.let(BloodPressureRecordMapper::toDomain)

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): BloodPressureRecord? = dao.getLatestByDate(dayStartMs, dayEndMs)?.let(BloodPressureRecordMapper::toDomain)
    }
