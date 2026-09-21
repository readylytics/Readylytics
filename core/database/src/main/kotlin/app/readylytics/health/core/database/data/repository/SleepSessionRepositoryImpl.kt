package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.SleepStageData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private fun List<SleepStageEntity>.toSleepStageData(): List<SleepStageData> =
    map { entity ->
        SleepStageData(
            stageType = entity.stageType,
            startTime = entity.startTime,
            endTime = entity.endTime,
            durationMinutes = entity.durationMinutes,
            sessionId = entity.sessionId,
        )
    }

private fun SleepSessionEntity.toSleepSessionData(): SleepSessionData =
    SleepSessionData(
        id = id,
        deviceName = deviceName,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = efficiency,
        deepSleepMinutes = deepSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        awakeMinutes = awakeMinutes,
        sleepScore = sleepScore,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
    )

@Singleton
class SleepSessionRepositoryImpl
    @Inject
    constructor(
        private val dao: SleepSessionDao,
        private val stageDao: SleepStageDao,
    ) : SleepSessionRepository {
        override fun observeSince(fromMs: Long): Flow<List<SleepSessionData>> =
            dao.observeSince(fromMs).map { entities -> entities.map { it.toSleepSessionData() } }

        override suspend fun getSince(fromMs: Long): List<SleepSessionData> =
            dao.getSince(fromMs).map { it.toSleepSessionData() }

        override suspend fun getInRange(
            fromMs: Long,
            toMs: Long,
        ): List<SleepSessionData> = dao.getBetween(fromMs, toMs).map { it.toSleepSessionData() }

        override suspend fun countSince(fromMs: Long): Int = dao.countSince(fromMs)

        override fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>> =
            stageDao.observeStagesForSession(sessionId).map { entities -> entities.toSleepStageData() }

        override suspend fun getSessionStages(sessionId: String): List<SleepStageData> =
            stageDao.getStagesForSession(sessionId).toSleepStageData()

        override suspend fun getSessionStages(sessionIds: List<String>): List<SleepStageData> {
            if (sessionIds.isEmpty()) return emptyList()
            return stageDao.getStagesForSessions(sessionIds).toSleepStageData()
        }

        override fun observeFirstSessionEndingInRange(
            fromMs: Long,
            toMs: Long,
        ): Flow<SleepSessionData?> =
            dao.observeFirstSessionEndingInRange(fromMs, toMs).map { entity ->
                entity?.toSleepSessionData()
            }
    }
