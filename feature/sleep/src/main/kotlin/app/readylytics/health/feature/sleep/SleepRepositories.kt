package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.domain.repository.DailyMetricsRepository
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles the repositories the Sleep tab needs, keeping the
 * [SleepViewModel] constructor within detekt's LongParameterList threshold.
 */
@Singleton
class SleepRepositories
    @Inject
    constructor(
        val dailySummary: DailySummaryRepository,
        val dailyMetrics: DailyMetricsRepository,
        val sleepSession: SleepSessionRepository,
        val heartRate: HeartRateRepository,
        val circadian: CircadianConsistencyRepository,
        val sleepLayout: SleepLayoutRepository,
    )
