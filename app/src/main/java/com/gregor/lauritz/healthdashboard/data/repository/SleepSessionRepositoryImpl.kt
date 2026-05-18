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
                        startTimeMs = entity.startTimeMs,
                        endTimeMs = entity.endTimeMs,
                        duration = entity.duration,
                        deepSleepMs = entity.deepSleepMs,
                        lightSleepMs = entity.lightSleepMs,
                        remSleepMs = entity.remSleepMs,
                        awakeSleepMs = entity.awakeSleepMs,
                    )
                }
            }

        override suspend fun getSince(fromMs: Long): List<SleepSessionData> =
            dao.getSince(fromMs).map { entity ->
                SleepSessionData(
                    id = entity.id,
                    deviceName = entity.deviceName,
                    startTimeMs = entity.startTimeMs,
                    endTimeMs = entity.endTimeMs,
                    duration = entity.duration,
                    deepSleepMs = entity.deepSleepMs,
                    lightSleepMs = entity.lightSleepMs,
                    remSleepMs = entity.remSleepMs,
                    awakeSleepMs = entity.awakeSleepMs,
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
                    startTimeMs = entity.startTimeMs,
                    endTimeMs = entity.endTimeMs,
                    duration = entity.duration,
                    deepSleepMs = entity.deepSleepMs,
                    lightSleepMs = entity.lightSleepMs,
                    remSleepMs = entity.remSleepMs,
                    awakeSleepMs = entity.awakeSleepMs,
                )
            }

        override suspend fun countSince(fromMs: Long): Int = dao.countSince(fromMs)
    }
