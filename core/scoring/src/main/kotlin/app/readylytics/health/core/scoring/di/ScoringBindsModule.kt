package app.readylytics.health.core.scoring.di

import app.readylytics.health.core.scoring.domain.scoring.AdaptiveRhrBaselineProvider
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.RhrBaselineProvider
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScoringBindsModule {
    @Binds
    @Singleton
    abstract fun bindScoringCalculator(impl: CompositeScoringCalculator): ScoringCalculator

    @Binds
    @Singleton
    abstract fun bindRhrBaselineProvider(impl: AdaptiveRhrBaselineProvider): RhrBaselineProvider
}
