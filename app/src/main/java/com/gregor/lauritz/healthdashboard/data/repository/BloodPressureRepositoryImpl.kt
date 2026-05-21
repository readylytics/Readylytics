package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.BloodPressureRecordDao
import com.gregor.lauritz.healthdashboard.data.local.entity.BloodPressureRecordEntity
import com.gregor.lauritz.healthdashboard.domain.repository.BloodPressureRepository
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
        ): Flow<List<BloodPressureRecordEntity>> =
            dao.observeSince(fromMs) // Note: approximation using observeSince

        override suspend fun getLatest(): BloodPressureRecordEntity? = dao.getLatest()

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): BloodPressureRecordEntity? = dao.getLatestByDate(dayStartMs, dayEndMs)
    }
