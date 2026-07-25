package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.mapper.DailySummaryMapper
import app.readylytics.health.data.preferences.scoringZone
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.SleepSessionData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryRepositoryImpl
    @Inject
    constructor(
        private val dao: DailySummaryDao,
        private val sleepSessionDao: SleepSessionDao,
        private val settingsRepository: SettingsRepository,
    ) : DailySummaryRepository {
        override fun observeLatest(): Flow<DailySummary?> =
            combine(dao.observeLatest(), settingsRepository.userPreferences) { entity, prefs ->
                entity?.let { DailySummaryMapper.toDomain(it, prefs.scoringZone()) }
            }

        override fun observeSince(fromMs: Long): Flow<List<DailySummary>> =
            combine(dao.observeSince(fromMs), settingsRepository.userPreferences) { list, prefs ->
                list.map { DailySummaryMapper.toDomain(it, prefs.scoringZone()) }
            }

        override fun observeByDate(dateMidnightMs: Long): Flow<DailySummary?> =
            combine(dao.observeByDate(dateMidnightMs), settingsRepository.userPreferences) { entity, prefs ->
                entity?.let { DailySummaryMapper.toDomain(it, prefs.scoringZone()) }
            }

        override suspend fun getByDate(dateMidnightMs: Long): DailySummary? {
            val prefs = settingsRepository.userPreferences.first()
            return dao.getByDate(dateMidnightMs)?.let { DailySummaryMapper.toDomain(it, prefs.scoringZone()) }
        }

        override suspend fun getSince(fromMs: Long): List<DailySummary> {
            val prefs = settingsRepository.userPreferences.first()
            return dao.getSince(fromMs).map { DailySummaryMapper.toDomain(it, prefs.scoringZone()) }
        }

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
