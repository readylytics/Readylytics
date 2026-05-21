package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SleepSessionRepositoryImpl
    @Inject
    constructor(
        private val dao: SleepSessionDao,
        private val stageDao: SleepStageDao,
    ) : SleepSessionRepository {
        override fun observeSince(fromMs: Long): Flow<List<SleepSessionData>> =
            dao.observeSince(fromMs).map { entities ->
                entities.map { entity ->
                    SleepSessionData(
                        id = entity.id,
                        deviceName = entity.deviceName,
                        startTime = entity.startTime,
                        endTime = entity.endTime,
                        durationMinutes = entity.durationMinutes,
                        efficiency = entity.efficiency,
                        deepSleepMinutes = entity.deepSleepMinutes,
                        lightSleepMinutes = entity.lightSleepMinutes,
                        remSleepMinutes = entity.remSleepMinutes,
                        awakeMinutes = entity.awakeMinutes,
                        sleepScore = entity.sleepScore,
                        startZoneOffsetSeconds = entity.startZoneOffsetSeconds,
                        endZoneOffsetSeconds = entity.endZoneOffsetSeconds,
                    )
                }
            }

        override suspend fun getSince(fromMs: Long): List<SleepSessionData> =
            dao.getSince(fromMs).map { entity ->
                SleepSessionData(
                    id = entity.id,
                    deviceName = entity.deviceName,
                    startTime = entity.startTime,
                    endTime = entity.endTime,
                    durationMinutes = entity.durationMinutes,
                    efficiency = entity.efficiency,
                    deepSleepMinutes = entity.deepSleepMinutes,
                    lightSleepMinutes = entity.lightSleepMinutes,
                    remSleepMinutes = entity.remSleepMinutes,
                    awakeMinutes = entity.awakeMinutes,
                    sleepScore = entity.sleepScore,
                    startZoneOffsetSeconds = entity.startZoneOffsetSeconds,
                    endZoneOffsetSeconds = entity.endZoneOffsetSeconds,
                )
            }

        override suspend fun getPaged(
            fromMs: Long,
            limit: Int,
            offset: Int,
        ): List<SleepSessionData> =
            dao.getPaged(fromMs, limit, offset).map { entity ->
                SleepSessionData(
                    id = entity.id,
                    deviceName = entity.deviceName,
                    startTime = entity.startTime,
                    endTime = entity.endTime,
                    durationMinutes = entity.durationMinutes,
                    efficiency = entity.efficiency,
                    deepSleepMinutes = entity.deepSleepMinutes,
                    lightSleepMinutes = entity.lightSleepMinutes,
                    remSleepMinutes = entity.remSleepMinutes,
                    awakeMinutes = entity.awakeMinutes,
                    sleepScore = entity.sleepScore,
                    startZoneOffsetSeconds = entity.startZoneOffsetSeconds,
                    endZoneOffsetSeconds = entity.endZoneOffsetSeconds,
                )
            }

        override suspend fun countSince(fromMs: Long): Int = dao.countSince(fromMs)

        override fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>> =
            stageDao.observeStagesForSession(sessionId).map { entities ->
                entities.map { entity ->
                    SleepStageData(
                        stageType = entity.stageType,
                        startTime = entity.startTime,
                        endTime = entity.endTime,
                        durationMinutes = entity.durationMinutes,
                    )
                }
            }

        override fun observeFirstSessionEndingInRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<SleepSessionData?> =
            dao.observeFirstSessionEndingInRange(fromMs, toMs).map { entity ->
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
