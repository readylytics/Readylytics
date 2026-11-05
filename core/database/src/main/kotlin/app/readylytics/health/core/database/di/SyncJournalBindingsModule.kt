package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.audit.RoomAuditTrailRepository
import app.readylytics.health.core.database.data.local.HealthMutationCoordinatorImpl
import app.readylytics.health.core.database.data.local.RoomDirtyRangeStore
import app.readylytics.health.core.database.data.local.RoomScanStagingStore
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Bindings for the append-only journals: scan staging, dirty ranges, mutation state and audit trail. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncJournalBindingsModule {
    @Binds
    @Singleton
    abstract fun bindScanStagingStore(impl: RoomScanStagingStore): ScanStagingStore

    @Binds
    @Singleton
    abstract fun bindDirtyRangeStore(impl: RoomDirtyRangeStore): DirtyRangeStore

    @Binds
    @Singleton
    abstract fun bindHealthMutationCoordinator(impl: HealthMutationCoordinatorImpl): HealthMutationCoordinator

    @Binds
    @Singleton
    abstract fun bindAuditTrailRepository(impl: RoomAuditTrailRepository): AuditTrailRepository
}
