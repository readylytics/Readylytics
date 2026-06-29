package app.readylytics.health.di

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.preferences.AboutPreferences
import app.readylytics.health.domain.preferences.BackupSettings
import app.readylytics.health.domain.preferences.DeviceSettings
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.HeartRateZoneSettings
import app.readylytics.health.domain.preferences.PhysiologySettings
import app.readylytics.health.domain.preferences.SleepSettings
import app.readylytics.health.domain.preferences.SyncSettings
import app.readylytics.health.domain.preferences.ThresholdSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.ForegroundSyncGateway
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.sync.HealthDataRefreshAdapter
import app.readylytics.health.domain.sync.HistoricalResyncController
import app.readylytics.health.domain.sync.HistoricalResyncControllerImpl
import app.readylytics.health.domain.user.UserProfileActions
import app.readylytics.health.domain.user.UserUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FeaturePortModule {
    @Binds
    @Singleton
    abstract fun bindUserPreferencesReader(impl: SettingsRepository): UserPreferencesReader

    @Binds
    @Singleton
    abstract fun bindAboutPreferences(impl: SettingsRepository): AboutPreferences

    @Binds
    @Singleton
    abstract fun bindPhysiologySettings(impl: SettingsRepository): PhysiologySettings

    @Binds
    @Singleton
    abstract fun bindHeartRateZoneSettings(impl: SettingsRepository): HeartRateZoneSettings

    @Binds
    @Singleton
    abstract fun bindSleepSettings(impl: SettingsRepository): SleepSettings

    @Binds
    @Singleton
    abstract fun bindThresholdSettings(impl: SettingsRepository): ThresholdSettings

    @Binds
    @Singleton
    abstract fun bindDisplaySettings(impl: SettingsRepository): DisplaySettings

    @Binds
    @Singleton
    abstract fun bindSyncSettings(impl: SettingsRepository): SyncSettings

    @Binds
    @Singleton
    abstract fun bindDeviceSettings(impl: SettingsRepository): DeviceSettings

    @Binds
    @Singleton
    abstract fun bindBackupSettings(impl: SettingsRepository): BackupSettings

    @Binds
    @Singleton
    abstract fun bindSelectedDateStore(impl: SelectedDateRepository): SelectedDateStore

    @Binds
    @Singleton
    abstract fun bindForegroundSyncGateway(impl: ForegroundSyncController): ForegroundSyncGateway

    @Binds
    @Singleton
    abstract fun bindHealthDataRefresh(impl: HealthDataRefreshAdapter): HealthDataRefresh

    @Binds
    @Singleton
    abstract fun bindHistoricalResyncController(impl: HistoricalResyncControllerImpl): HistoricalResyncController

    @Binds
    @Singleton
    abstract fun bindUserProfileActions(impl: UserUseCase): UserProfileActions
}
