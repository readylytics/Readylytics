package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.dao.AuditEventDao
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.dao.ScanTypeStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** DAOs for the dirty-range, mutation-state, scan-staging and audit journals. */
@Module
@InstallIn(SingletonComponent::class)
object JournalDaoModule {
    @Provides
    fun provideDirtyRangeDao(db: HealthDatabase): DirtyRangeDao = db.dirtyRangeDao()

    @Provides
    fun provideHealthMutationStateDao(db: HealthDatabase): HealthMutationStateDao = db.healthMutationStateDao()

    @Provides
    fun provideScanStagingDao(db: HealthDatabase): ScanStagingDao = db.scanStagingDao()

    @Provides
    fun provideScanTypeStateDao(db: HealthDatabase): ScanTypeStateDao = db.scanTypeStateDao()

    @Provides
    fun provideAuditEventDao(db: HealthDatabase): AuditEventDao = db.auditEventDao()
}
