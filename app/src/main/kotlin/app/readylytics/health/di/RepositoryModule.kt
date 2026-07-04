package app.readylytics.health.di

import app.readylytics.health.data.audit.RoomAuditTrailRepository
import app.readylytics.health.data.local.RoomHealthIngestionStore
import app.readylytics.health.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.data.repository.BloodPressureRepositoryImpl
import app.readylytics.health.data.repository.BodyFatRepositoryImpl
import app.readylytics.health.data.repository.DailyMetricsRepositoryImpl
import app.readylytics.health.data.repository.DailySummaryRepositoryImpl
import app.readylytics.health.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.data.repository.InsightDismissalRepositoryImpl
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.data.repository.WeightRepositoryImpl
import app.readylytics.health.data.repository.WorkoutRepositoryImpl
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.repository.BloodPressureRepository
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.AdaptiveRhrBaselineProvider
import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.RhrBaselineProvider
import app.readylytics.health.domain.scoring.ScoringCalculator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDailySummaryRepository(impl: DailySummaryRepositoryImpl): DailySummaryRepository

    @Binds
    @Singleton
    abstract fun bindDailyMetricsRepository(impl: DailyMetricsRepositoryImpl): DailyMetricsRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

    @Binds
    @Singleton
    abstract fun bindHeartRateRepository(impl: HeartRateRepositoryImpl): HeartRateRepository

    @Binds
    @Singleton
    abstract fun bindWeightRepository(impl: WeightRepositoryImpl): WeightRepository

    @Binds
    @Singleton
    abstract fun bindBodyFatRepository(impl: BodyFatRepositoryImpl): BodyFatRepository

    @Binds
    @Singleton
    abstract fun bindBloodPressureRepository(impl: BloodPressureRepositoryImpl): BloodPressureRepository

    @Binds
    @Singleton
    abstract fun bindInsightDismissalRepository(impl: InsightDismissalRepositoryImpl): InsightDismissalRepository

    @Binds
    @Singleton
    abstract fun bindAuditTrailRepository(impl: RoomAuditTrailRepository): AuditTrailRepository

    @Binds
    @Singleton
    abstract fun bindScoringHistoryRepository(impl: ScoringHistoryRepositoryImpl): ScoringHistoryRepository

    @Binds
    @Singleton
    abstract fun bindScoringRepository(impl: ScoringRepositoryImpl): ScoringRepository

    @Binds
    @Singleton
    abstract fun bindScoringCalculator(impl: CompositeScoringCalculator): ScoringCalculator

    @Binds
    @Singleton
    abstract fun bindRhrBaselineProvider(impl: AdaptiveRhrBaselineProvider): RhrBaselineProvider

    @Binds
    @Singleton
    abstract fun bindHealthChangeTokenStore(
        impl: app.readylytics.health.data.preferences.HealthChangeTokenStoreImpl,
    ): app.readylytics.health.domain.sync.HealthChangeTokenStore

    @Binds
    @Singleton
    abstract fun bindSelectedSourcePruner(
        impl: app.readylytics.health.data.local.SelectedSourcePrunerImpl,
    ): app.readylytics.health.domain.sync.SelectedSourcePruner

    @Binds
    @Singleton
    abstract fun bindResyncCheckpointStore(
        impl: app.readylytics.health.data.preferences.ResyncCheckpointStoreImpl,
    ): app.readylytics.health.domain.sync.ResyncCheckpointStore

    @Binds
    @Singleton
    abstract fun bindHealthIngestionStore(
        impl: RoomHealthIngestionStore,
    ): app.readylytics.health.domain.sync.HealthIngestionStore

    @Binds
    @Singleton
    abstract fun bindSessionLinkReconciler(
        impl: SessionLinkReconcilerImpl,
    ): app.readylytics.health.domain.sync.link.SessionLinkReconciler

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: app.readylytics.health.data.preferences.SettingsRepository,
    ): app.readylytics.health.domain.preferences.SettingsRepository

    @Binds
    @Singleton
    abstract fun bindCircadianThresholdPreferences(
        impl: app.readylytics.health.data.preferences.DataStoreCircadianThresholdPreferences,
    ): app.readylytics.health.domain.preferences.CircadianThresholdPreferences

    @Binds
    @Singleton
    abstract fun bindCardConfigurationRepository(
        impl: app.readylytics.health.data.preferences.CardConfigurationRepositoryImpl,
    ): app.readylytics.health.domain.dashboard.CardConfigurationRepository
}
