package com.gregor.lauritz.healthdashboard.di

import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.DataStoreCircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculatorImpl
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
    abstract fun bindScoringCalculator(impl: ScoringCalculatorImpl): ScoringCalculator

    @Binds
    @Singleton
    abstract fun bindCircadianThresholdPreferences(
        impl: DataStoreCircadianThresholdPreferences,
    ): CircadianThresholdPreferences
}
