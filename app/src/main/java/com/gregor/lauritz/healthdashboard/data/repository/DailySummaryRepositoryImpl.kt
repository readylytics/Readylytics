package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryRepositoryImpl
    @Inject
    constructor(
        private val dao: DailySummaryDao,
    ) : DailySummaryRepository {
        override fun observeLatest(): Flow<DailySummary?> =
            dao.observeLatest().map { entity ->
                entity?.let { DailySummaryMapper.toDomain(it) }
            }

        override fun observeSince(fromMs: Long): Flow<List<DailySummary>> =
            dao.observeSince(fromMs).map { list ->
                list.map { DailySummaryMapper.toDomain(it) }
            }

        override fun observeByDate(dateMidnightMs: Long): Flow<DailySummary?> =
            dao.observeByDate(dateMidnightMs).map { entity ->
                entity?.let { DailySummaryMapper.toDomain(it) }
            }

        override suspend fun getByDate(dateMidnightMs: Long): DailySummary? =
            dao.getByDate(dateMidnightMs)?.let { DailySummaryMapper.toDomain(it) }

        override suspend fun getSince(fromMs: Long): List<DailySummary> =
            dao.getSince(fromMs).map { DailySummaryMapper.toDomain(it) }
    }
