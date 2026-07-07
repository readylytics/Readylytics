package app.readylytics.health.di

import app.readylytics.health.data.backup.LocalBackupServiceImpl
import app.readylytics.health.data.backup.LocalRestoreServiceImpl
import app.readylytics.health.data.crashreport.CrashReportStoreImpl
import app.readylytics.health.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.domain.backup.BackupService
import app.readylytics.health.domain.backup.RestoreService
import app.readylytics.health.domain.crashreport.CrashReportStore
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.util.ResourceProvider
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
    abstract fun bindCrashReportStore(impl: CrashReportStoreImpl): CrashReportStore

    @Binds
    @Singleton
    abstract fun bindSleepSessionRepository(impl: SleepSessionRepositoryImpl): SleepSessionRepository

    @Binds
    @Singleton
    abstract fun bindTimezoneProvider(
        impl: app.readylytics.health.data.util.TimezoneProviderImpl,
    ): app.readylytics.health.domain.util.TimezoneProvider

    @Binds
    @Singleton
    abstract fun bindEncryptionManager(
        impl: app.readylytics.health.data.security.EncryptionManager,
    ): app.readylytics.health.domain.security.EncryptionManager
}
