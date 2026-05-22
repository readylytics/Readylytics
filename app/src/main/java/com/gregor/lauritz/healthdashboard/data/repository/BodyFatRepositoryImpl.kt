package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.BodyFatRecordDao
import com.gregor.lauritz.healthdashboard.data.local.entity.BodyFatRecordEntity
import com.gregor.lauritz.healthdashboard.domain.repository.BodyFatRepository
import kotlinx.coroutines.flow.Flow
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
        ): List<BodyFatRecordEntity> = dao.getByTimeRange(fromMs, toMs)

        override fun observeByDateRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<List<BodyFatRecordEntity>> = dao.observeByTimeRange(fromMs, toMs)

        override suspend fun getLatest(): BodyFatRecordEntity? = dao.getLatest()

        override suspend fun getLatestByDate(
            dayStartMs: Long,
            dayEndMs: Long,
        ): BodyFatRecordEntity? = dao.getLatestByDate(dayStartMs, dayEndMs)
    }
