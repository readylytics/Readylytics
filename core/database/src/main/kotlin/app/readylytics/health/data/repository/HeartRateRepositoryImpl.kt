package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.domain.model.HrRangeAggregate
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.HrvRecordData
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

        override fun observeAggregateByTimeRange(
            startMs: Long,
            endMs: Long,
        ): Flow<HrRangeAggregate?> = heartRateDao.observeAggregateByTimeRange(startMs, endMs)

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
