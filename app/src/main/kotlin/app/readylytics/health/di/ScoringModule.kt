package app.readylytics.health.di

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.preferences.CircadianThresholdPreferences
import app.readylytics.health.data.preferences.DataStoreCircadianThresholdPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.scoring.AdaptiveRhrBaselineProvider
import app.readylytics.health.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.ComputeHistoricalBaselinesUseCase
import app.readylytics.health.domain.scoring.HrMaxProvider
import app.readylytics.health.domain.scoring.HrvBaselineProvider
import app.readylytics.health.domain.scoring.LoadMetricsProvider
import app.readylytics.health.domain.scoring.RasProvider
import app.readylytics.health.domain.scoring.RhrBaselineProvider
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class ScoringModule {
    @Provides
    @Singleton
    fun provideComputeHistoricalBaselinesUseCase(
        baselineComputer: BaselineComputer,
        loadScoringStrategy: LoadScoringStrategy,
    ): ComputeHistoricalBaselinesUseCase = ComputeHistoricalBaselinesUseCase(baselineComputer, loadScoringStrategy)

    @Provides
    @Singleton
    fun provideBackfillHistoricalBaselinesUseCase(
        dailySummaryDao: DailySummaryDao,
        settingsRepository: SettingsRepository,
        computeHistoricalBaselines: ComputeHistoricalBaselinesUseCase,
        transactionRunner: TransactionRunner,
    ): BackfillHistoricalBaselinesUseCase =
        BackfillHistoricalBaselinesUseCase(
            dailySummaryDao,
            settingsRepository,
            computeHistoricalBaselines,
            transactionRunner,
        )

    @Provides
    @Singleton
    fun provideRhrBaselineProvider(
        dailySummaryDao: DailySummaryDao,
        settingsRepository: SettingsRepository,
        baselineComputer: BaselineComputer,
    ): RhrBaselineProvider = AdaptiveRhrBaselineProvider(dailySummaryDao, settingsRepository, baselineComputer)

    @Provides
    @Singleton
    fun provideHrvBaselineProvider(
        dailySummaryDao: DailySummaryDao,
        settingsRepository: SettingsRepository,
        baselineComputer: BaselineComputer,
    ): HrvBaselineProvider = HrvBaselineProvider(dailySummaryDao, settingsRepository, baselineComputer)

    @Provides
    @Singleton
    fun provideHrMaxProvider(
        dailySummaryDao: DailySummaryDao,
        settingsRepository: SettingsRepository,
    ): HrMaxProvider = HrMaxProvider(dailySummaryDao, settingsRepository)

    @Provides
    @Singleton
    fun provideRasProvider(dailySummaryDao: DailySummaryDao): RasProvider = RasProvider(dailySummaryDao)

    @Provides
    @Singleton
    fun provideLoadMetricsProvider(dailySummaryDao: DailySummaryDao): LoadMetricsProvider =
        LoadMetricsProvider(dailySummaryDao)

    companion object {
        @Provides
        @Singleton
        fun provideScoringRepository(impl: ScoringRepositoryImpl): ScoringRepository = impl

        @Provides
        @Singleton
        fun provideScoringCalculator(impl: CompositeScoringCalculator): ScoringCalculator = impl

        @Provides
        @Singleton
        fun provideCircadianThresholdPreferences(
            impl: DataStoreCircadianThresholdPreferences,
        ): CircadianThresholdPreferences = impl
    }
}
