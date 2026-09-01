package app.readylytics.health.core.healthconnect.di

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import app.readylytics.health.core.healthconnect.data.healthconnect.HealthConnectRepositoryImpl
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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

    companion object {
        @Provides
        @Singleton
        fun provideHealthConnectClient(
            @ApplicationContext context: Context,
        ): HealthConnectClient = HealthConnectClient.getOrCreate(context)
    }
}
