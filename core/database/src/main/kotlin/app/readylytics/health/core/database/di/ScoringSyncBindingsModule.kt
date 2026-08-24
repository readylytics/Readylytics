package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.RoomHealthIngestionStore
import app.readylytics.health.core.database.data.local.SelectedSourcePrunerImpl
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.SelectedDateRepository
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.ScoringRepository
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
abstract class ScoringSyncBindingsModule {
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
}
