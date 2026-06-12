package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummaryMapper
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.util.toMidnightEpochMilli
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [DailySummaryDao] + [DailySummaryMapper] + [DailyMetricsMapper] to expose the
 * single rounding-safe metrics surface. Preferences (override baselines) flow into the
 * mapper so baseline derivation stays centralized; `observe*` combine the summary Flow
 * with the preferences Flow, and the suspend variant snapshots prefs via `first()`.
 */
@Singleton
class DailyMetricsRepositoryImpl
    @Inject
    constructor(
        private val dao: DailySummaryDao,
        private val settingsRepository: SettingsRepository,
    ) : DailyMetricsRepository {
        override suspend fun getDailyMetrics(date: LocalDate): DailyMetrics? {
            val entity = dao.getByDate(date.toMidnightEpochMilli()) ?: return null
            val prefs = settingsRepository.userPreferences.first()
            return DailyMetricsMapper.toMetrics(DailySummaryMapper.toDomain(entity), prefs)
        }

        override fun observeByDate(date: LocalDate): Flow<DailyMetrics?> =
            combine(
                dao.observeByDate(date.toMidnightEpochMilli()),
                settingsRepository.userPreferences,
            ) { entity, prefs ->
                entity?.let { DailyMetricsMapper.toMetrics(DailySummaryMapper.toDomain(it), prefs) }
            }

        override fun observeSince(fromMs: Long): Flow<List<DailyMetrics>> =
            combine(
                dao.observeSince(fromMs),
                settingsRepository.userPreferences,
            ) { entities, prefs ->
                entities.map { DailyMetricsMapper.toMetrics(DailySummaryMapper.toDomain(it), prefs) }
            }
    }
