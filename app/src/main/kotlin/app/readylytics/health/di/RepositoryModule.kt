package app.readylytics.health.di

import app.readylytics.health.data.repository.BloodPressureRepositoryImpl
import app.readylytics.health.data.repository.BodyFatRepositoryImpl
import app.readylytics.health.data.repository.DailyMetricsRepositoryImpl
import app.readylytics.health.data.repository.DailySummaryRepositoryImpl
import app.readylytics.health.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.data.repository.InsightDismissalRepositoryImpl
import app.readylytics.health.data.repository.WeightRepositoryImpl
import app.readylytics.health.data.repository.WorkoutRepositoryImpl
import app.readylytics.health.domain.repository.BloodPressureRepository
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.domain.repository.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDailySummaryRepository(impl: DailySummaryRepositoryImpl): DailySummaryRepository

    @Binds
    @Singleton
    abstract fun bindDailyMetricsRepository(impl: DailyMetricsRepositoryImpl): DailyMetricsRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

    @Binds
    @Singleton
    abstract fun bindHeartRateRepository(impl: HeartRateRepositoryImpl): HeartRateRepository

    @Binds
    @Singleton
    abstract fun bindWeightRepository(impl: WeightRepositoryImpl): WeightRepository

    @Binds
    @Singleton
    abstract fun bindBodyFatRepository(impl: BodyFatRepositoryImpl): BodyFatRepository

    @Binds
    @Singleton
    abstract fun bindBloodPressureRepository(impl: BloodPressureRepositoryImpl): BloodPressureRepository

    @Binds
    @Singleton
    abstract fun bindInsightDismissalRepository(impl: InsightDismissalRepositoryImpl): InsightDismissalRepository
}
