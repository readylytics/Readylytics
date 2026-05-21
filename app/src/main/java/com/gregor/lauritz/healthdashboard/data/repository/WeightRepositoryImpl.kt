package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.WeightRecordDao
import com.gregor.lauritz.healthdashboard.data.local.entity.WeightRecordEntity
import com.gregor.lauritz.healthdashboard.domain.repository.WeightRepository
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
        ): Flow<List<WeightRecordEntity>> =
            dao.observeSince(fromMs) // Note: approximation using observeSince

        override suspend fun getLatest(): WeightRecordEntity? = dao.getLatest()

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): WeightRecordEntity? = dao.getLatestByDate(dayStartMs, dayEndMs)
    }
