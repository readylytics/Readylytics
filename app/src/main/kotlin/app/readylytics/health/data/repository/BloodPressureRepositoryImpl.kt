package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.domain.repository.BloodPressureRepository
import kotlinx.coroutines.flow.Flow
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
        ): List<BloodPressureRecordEntity> = dao.getByTimeRange(fromMs, toMs)

        override fun observeByDateRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<List<BloodPressureRecordEntity>> = dao.observeByTimeRange(fromMs, toMs)

        override suspend fun getLatest(): BloodPressureRecordEntity? = dao.getLatest()

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): BloodPressureRecordEntity? = dao.getLatestByDate(dayStartMs, dayEndMs)
    }
