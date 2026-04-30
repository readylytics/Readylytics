package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryRepository @Inject constructor(
    private val dailySummaryDao: DailySummaryDao,
    private val sleepSessionDao: SleepSessionDao,
) {
    fun observeByDate(dateMs: Long): Flow<DailySummary?> =
        dailySummaryDao.observeByDate(dateMs).map { entity -> entity?.let { DailySummaryMapper.toDomain(it) } }

    fun observeLatest(): Flow<DailySummary?> =
        dailySummaryDao.observeLatest().map { entity -> entity?.let { DailySummaryMapper.toDomain(it) } }

    fun observeSince(fromMs: Long): Flow<List<DailySummary>> =
        dailySummaryDao.observeSince(fromMs).map { list -> list.map { DailySummaryMapper.toDomain(it) } }

    fun observeFirstSessionEndingInRange(fromMs: Long, toMs: Long): Flow<SleepSessionEntity?> =
        sleepSessionDao.observeFirstSessionEndingInRange(fromMs, toMs)
}
