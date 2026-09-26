package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthMutationCoordinatorImpl
import app.readylytics.health.core.database.data.local.RoomDirtyRangeStore
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Supplementary scoring and sync bindings kept in their own module rather than appended to
 * [ScoringSyncBindingsModule] so neither class trips detekt's `TooManyFunctions` threshold,
 * mirroring [DatabaseRepositorySupplementModule] and [DaoProvidersSupplementModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScoringSyncBindingsSupplementModule {
    @Binds
    @Singleton
    abstract fun bindDirtyRangeStore(impl: RoomDirtyRangeStore): DirtyRangeStore

    @Binds
    @Singleton
    abstract fun bindHealthMutationCoordinator(impl: HealthMutationCoordinatorImpl): HealthMutationCoordinator
}
