package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.audit.RoomAuditTrailRepository
import app.readylytics.health.core.database.data.repository.InsightDismissalRepositoryImpl
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.repository.InsightDismissalRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Supplementary repository bindings kept in their own module rather than appended to
 * [DatabaseRepositoryModule] so neither class trips detekt's `TooManyFunctions` threshold,
 * mirroring [DaoProvidersSupplementModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseRepositorySupplementModule {
    @Binds
    @Singleton
    abstract fun bindInsightDismissalRepository(impl: InsightDismissalRepositoryImpl): InsightDismissalRepository

    @Binds
    @Singleton
    abstract fun bindAuditTrailRepository(impl: RoomAuditTrailRepository): AuditTrailRepository
}
