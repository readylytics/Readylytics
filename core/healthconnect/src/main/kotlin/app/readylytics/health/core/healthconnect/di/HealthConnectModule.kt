package app.readylytics.health.core.healthconnect.di

import app.readylytics.health.core.healthconnect.data.healthconnect.HealthConnectRepositoryImpl
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

    @Binds
    @Singleton
    abstract fun bindHealthChangeSynchronizer(
        impl: app.readylytics.health.core.healthconnect.data.healthconnect.HealthChangeSynchronizerImpl,
    ): app.readylytics.health.core.healthconnect.domain.sync.HealthChangeSynchronizer
}
