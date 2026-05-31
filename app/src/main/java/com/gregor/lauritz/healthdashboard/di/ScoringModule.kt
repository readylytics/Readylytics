package com.gregor.lauritz.healthdashboard.di

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.DataStoreCircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.repository.ScoringRepositoryImpl
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.BackfillHistoricalBaselinesUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.BaselineComputer
import com.gregor.lauritz.healthdashboard.domain.scoring.CompositeScoringCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.ComputeHistoricalBaselinesUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.strategies.LoadScoringStrategy
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
        computeHistoricalBaselines: ComputeHistoricalBaselinesUseCase,
    ): BackfillHistoricalBaselinesUseCase =
        BackfillHistoricalBaselinesUseCase(
            dailySummaryDao,
            computeHistoricalBaselines,
        )

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
