package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.audit.RoomAuditTrailRepository
import app.readylytics.health.data.local.RoomHealthIngestionStore
import app.readylytics.health.data.local.SelectedSourcePrunerImpl
import app.readylytics.health.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.database.data.repository.BloodPressureRepositoryImpl
import app.readylytics.health.core.database.data.repository.BodyFatRepositoryImpl
import app.readylytics.health.core.database.data.repository.DailyMetricsRepositoryImpl
import app.readylytics.health.core.database.data.repository.DailySummaryRepositoryImpl
import app.readylytics.health.core.database.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.core.database.data.repository.InsightDismissalRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.SelectedDateRepository
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.core.database.data.repository.WeightRepositoryImpl
import app.readylytics.health.core.database.data.repository.WorkoutRepositoryImpl
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.repository.BloodPressureRepository
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseRepositoryModule {
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
    abstract fun bindSelectedSourcePruner(impl: SelectedSourcePrunerImpl): SelectedSourcePruner

    @Binds
    @Singleton
    abstract fun bindHealthIngestionStore(impl: RoomHealthIngestionStore): HealthIngestionStore

    @Binds
    @Singleton
    abstract fun bindSelectedDateStore(impl: SelectedDateRepository): SelectedDateStore

    @Binds
    @Singleton
    abstract fun bindSessionLinkReconciler(impl: SessionLinkReconcilerImpl): SessionLinkReconciler

    @Binds
    @Singleton
    abstract fun bindSleepSessionRepository(impl: SleepSessionRepositoryImpl): SleepSessionRepository
}
