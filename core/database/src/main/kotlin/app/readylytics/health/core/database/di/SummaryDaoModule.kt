package app.readylytics.health.core.database.di

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.InsightDismissalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** DAOs for derived daily summaries and insight dismissals. */
@Module
@InstallIn(SingletonComponent::class)
object SummaryDaoModule {
    @Provides
    fun provideDailySummaryDao(db: HealthDatabase): DailySummaryDao = db.dailySummaryDao()

    @Provides
    fun provideInsightDismissalDao(db: HealthDatabase): InsightDismissalDao = db.insightDismissalDao()
}
