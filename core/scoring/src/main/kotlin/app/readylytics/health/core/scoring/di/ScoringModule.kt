package app.readylytics.health.core.scoring.di

import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.RasSourceModeBootstrapUseCase
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
        scoringHistoryRepository: ScoringHistoryRepository,
    ): RasSourceModeBootstrapUseCase = RasSourceModeBootstrapUseCase(settingsRepository, scoringHistoryRepository)
}
