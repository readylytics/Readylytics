package app.readylytics.health.di

import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.scoring.RasSourceModeBootstrapUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScoringModule {
    @Provides
    @Singleton
    fun provideRasSourceModeBootstrapUseCase(
        settingsRepository: SettingsRepository,
        dailySummaryDao: DailySummaryDao,
    ): RasSourceModeBootstrapUseCase = RasSourceModeBootstrapUseCase(settingsRepository, dailySummaryDao)
}
