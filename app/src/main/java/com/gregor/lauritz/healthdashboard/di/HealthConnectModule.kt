package com.gregor.lauritz.healthdashboard.di

import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepositoryImpl
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthConnectModule {
    @Binds
    @Singleton
    abstract fun bindHealthConnectRepository(impl: HealthConnectRepositoryImpl): HealthConnectRepository
}
