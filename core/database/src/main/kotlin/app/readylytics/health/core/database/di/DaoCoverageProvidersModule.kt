package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateRefreshStagingDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageMaintenanceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * WP-17 coverage/contribution and refresh-staging DAOs. Kept in their own module rather than
 * appended to [DaoProvidersSupplementModule] so neither object trips detekt's `TooManyFunctions`
 * threshold, mirroring the existing [DaoJournalProvidersModule] split.
 */
@Module
@InstallIn(SingletonComponent::class)
object DaoCoverageProvidersModule {
    @Provides
    fun provideMinuteCoverageDao(db: HealthDatabase): MinuteCoverageDao = db.minuteCoverageDao()

    @Provides
    fun provideMinuteCoverageMaintenanceDao(db: HealthDatabase): MinuteCoverageMaintenanceDao =
        db.minuteCoverageMaintenanceDao()

    @Provides
    fun provideHeartRateRefreshStagingDao(db: HealthDatabase): HeartRateRefreshStagingDao =
        db.heartRateRefreshStagingDao()
}
