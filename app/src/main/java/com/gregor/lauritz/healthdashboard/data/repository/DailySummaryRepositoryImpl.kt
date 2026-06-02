package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.DailySummaryMapper
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryRepositoryImpl
    @Inject
    constructor(
        private val dao: DailySummaryDao,
        private val sleepSessionDao: SleepSessionDao,
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

        override fun observeFirstSessionEndingInRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<SleepSessionData?> =
            sleepSessionDao
                .observeFirstSessionEndingInRange(fromMs, toMs)
                .map { entity ->
                    entity?.let {
                        SleepSessionData(
                            id = it.id,
                            deviceName = it.deviceName,
                            startTime = it.startTime,
                            endTime = it.endTime,
                            durationMinutes = it.durationMinutes,
                            efficiency = it.efficiency,
                            deepSleepMinutes = it.deepSleepMinutes,
                            lightSleepMinutes = it.lightSleepMinutes,
                            remSleepMinutes = it.remSleepMinutes,
                            awakeMinutes = it.awakeMinutes,
                            sleepScore = it.sleepScore,
                            startZoneOffsetSeconds = it.startZoneOffsetSeconds,
                            endZoneOffsetSeconds = it.endZoneOffsetSeconds,
                        )
                    }
                }
    }
