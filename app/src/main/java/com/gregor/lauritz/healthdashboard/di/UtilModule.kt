package com.gregor.lauritz.healthdashboard.di

import com.gregor.lauritz.healthdashboard.data.backup.LocalBackupServiceImpl
import com.gregor.lauritz.healthdashboard.data.backup.LocalRestoreServiceImpl
import com.gregor.lauritz.healthdashboard.data.repository.SleepSessionRepositoryImpl
import com.gregor.lauritz.healthdashboard.domain.backup.BackupService
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreService
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionRepository
import com.gregor.lauritz.healthdashboard.domain.util.ResourceProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UtilModule {
    @Binds
    @Singleton
    abstract fun bindResourceProvider(impl: AndroidResourceProvider): ResourceProvider

    @Binds
    @Singleton
    abstract fun bindBackupService(impl: LocalBackupServiceImpl): BackupService

    @Binds
    @Singleton
    abstract fun bindRestoreService(impl: LocalRestoreServiceImpl): RestoreService

    @Binds
    @Singleton
    abstract fun bindSleepSessionRepository(impl: SleepSessionRepositoryImpl): SleepSessionRepository

    @Binds
    @Singleton
    abstract fun bindTimezoneProvider(
        impl: com.gregor.lauritz.healthdashboard.data.util.TimezoneProviderImpl,
    ): com.gregor.lauritz.healthdashboard.domain.util.TimezoneProvider
}
