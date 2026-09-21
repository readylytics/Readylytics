package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DaoJournalProvidersModule {
    @Provides
    fun provideDirtyRangeDao(db: HealthDatabase): DirtyRangeDao = db.dirtyRangeDao()

    @Provides
    fun provideHealthMutationStateDao(db: HealthDatabase): HealthMutationStateDao = db.healthMutationStateDao()
}
