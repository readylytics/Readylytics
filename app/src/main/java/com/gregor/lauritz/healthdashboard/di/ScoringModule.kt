package com.gregor.lauritz.healthdashboard.di

import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.DataStoreCircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.repository.ScoringRepositoryImpl
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ComposeScoringCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScoringModule {
    @Binds
    @Singleton
    abstract fun bindScoringRepository(impl: ScoringRepositoryImpl): ScoringRepository

    @Binds
    @Singleton
    abstract fun bindScoringCalculator(impl: ComposeScoringCalculator): ScoringCalculator

    @Binds
    @Singleton
    abstract fun bindCircadianThresholdPreferences(
        impl: DataStoreCircadianThresholdPreferences,
    ): CircadianThresholdPreferences
}
