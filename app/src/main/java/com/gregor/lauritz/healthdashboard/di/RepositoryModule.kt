package com.gregor.lauritz.healthdashboard.di

import com.gregor.lauritz.healthdashboard.data.repository.BloodPressureRepositoryImpl
import com.gregor.lauritz.healthdashboard.data.repository.BodyFatRepositoryImpl
import com.gregor.lauritz.healthdashboard.data.repository.DailyMetricsRepositoryImpl
import com.gregor.lauritz.healthdashboard.data.repository.DailySummaryRepositoryImpl
import com.gregor.lauritz.healthdashboard.data.repository.HeartRateRepositoryImpl
import com.gregor.lauritz.healthdashboard.data.repository.WeightRepositoryImpl
import com.gregor.lauritz.healthdashboard.data.repository.WorkoutRepositoryImpl
import com.gregor.lauritz.healthdashboard.domain.repository.BloodPressureRepository
import com.gregor.lauritz.healthdashboard.domain.repository.BodyFatRepository
import com.gregor.lauritz.healthdashboard.domain.repository.DailyMetricsRepository
import com.gregor.lauritz.healthdashboard.domain.repository.DailySummaryRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HeartRateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.WeightRepository
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutRepository
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
}
