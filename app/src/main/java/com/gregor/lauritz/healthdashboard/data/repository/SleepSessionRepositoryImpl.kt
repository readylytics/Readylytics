package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SleepSessionRepositoryImpl
    @Inject
    constructor(
        private val dao: SleepSessionDao,
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
                        deepSleepMinutes = entity.deepSleepMinutes,
                        lightSleepMinutes = entity.lightSleepMinutes,
                        remSleepMinutes = entity.remSleepMinutes,
                        awakeMinutes = entity.awakeMinutes,
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
                    deepSleepMinutes = entity.deepSleepMinutes,
                    lightSleepMinutes = entity.lightSleepMinutes,
                    remSleepMinutes = entity.remSleepMinutes,
                    awakeMinutes = entity.awakeMinutes,
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
                    deepSleepMinutes = entity.deepSleepMinutes,
                    lightSleepMinutes = entity.lightSleepMinutes,
                    remSleepMinutes = entity.remSleepMinutes,
                    awakeMinutes = entity.awakeMinutes,
                )
            }

        override suspend fun countSince(fromMs: Long): Int = dao.countSince(fromMs)
    }
