package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRecordData
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HrvRecordData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeartRateRepositoryImpl
    @Inject
    constructor(
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
    ) : HeartRateRepository {
        override fun observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordData>> =
            heartRateDao.observeSleepHrSince(fromMs).map { list ->
                list.map { mapToDomain(it) }
            }

        override suspend fun getMinHrInRange(
            startTimeMs: Long,
            endTimeMs: Long,
        ): Int? = heartRateDao.getMinHrInRange(startTimeMs, endTimeMs)

        override suspend fun getByTimeRange(
            startTimeMs: Long,
            endTimeMs: Long,
        ): List<HeartRateRecordData> = heartRateDao.getByTimeRange(startTimeMs, endTimeMs).map { mapToDomain(it) }

        override fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordData>> =
            hrvDao.observeSleepHrvSince(fromMs).map { list ->
                list.map { mapToDomain(it) }
            }

        override fun observeByTimeRange(
            startMs: Long,
            endMs: Long,
        ): Flow<List<HeartRateRecordData>> =
            heartRateDao.observeByTimeRange(startMs, endMs).map { list ->
                list.map { mapToDomain(it) }
            }

        private fun mapToDomain(entity: HeartRateRecordEntity): HeartRateRecordData =
            HeartRateRecordData(
                id = entity.id,
                timestampMs = entity.timestampMs,
                beatsPerMinute = entity.beatsPerMinute,
                recordType = entity.recordType,
                sessionId = entity.sessionId,
                deviceName = entity.deviceName,
            )

        private fun mapToDomain(entity: HrvRecordEntity): HrvRecordData =
            HrvRecordData(
                id = entity.id,
                timestampMs = entity.timestampMs,
                rmssdMs = entity.rmssdMs,
                recordType = entity.recordType,
                sessionId = entity.sessionId,
                deviceName = entity.deviceName,
            )
    }
