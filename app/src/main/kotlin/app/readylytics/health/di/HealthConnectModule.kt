package app.readylytics.health.di

import app.readylytics.health.data.healthconnect.HealthConnectRepositoryImpl
import app.readylytics.health.domain.repository.HealthConnectRepository
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
