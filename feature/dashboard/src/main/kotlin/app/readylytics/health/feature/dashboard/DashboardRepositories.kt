package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.domain.cache.DailyMetricCache
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.InsightDismissalRepository
import app.readylytics.health.core.model.domain.service.BodyTemperatureBaselineProvider
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import javax.inject.Inject

class DashboardRepositories
    @Inject
    constructor(
        val dailySummary: DailySummaryRepository,
        val selectedDate: SelectedDateStore,
        val settings: UserPreferencesReader,
        val cardConfig: CardConfigurationRepository,
        val circadian: CircadianConsistencyRepository,
        val dailyMetricCache: DailyMetricCache,
        val heartRate: HeartRateRepository,
        val insightDismissal: InsightDismissalRepository,
        val bodyTemperatureBaselineProvider: BodyTemperatureBaselineProvider,
    )
