package app.readylytics.health.architecture

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
import kotlin.test.assertTrue
import org.junit.Test

class FeaturePortBindingTest {
    @Test
    fun `concrete implementations satisfy feature-facing ports`() {
        assertImplements<SettingsRepository, UserPreferencesReader>()
        assertImplements<SettingsRepository, AboutPreferences>()
        assertImplements<SettingsRepository, PhysiologySettings>()
        assertImplements<SettingsRepository, HeartRateZoneSettings>()
        assertImplements<SettingsRepository, SleepSettings>()
        assertImplements<SettingsRepository, ThresholdSettings>()
        assertImplements<SettingsRepository, DisplaySettings>()
        assertImplements<SettingsRepository, SyncSettings>()
        assertImplements<SettingsRepository, DeviceSettings>()
        assertImplements<SettingsRepository, BackupSettings>()
        assertImplements<SelectedDateRepository, SelectedDateStore>()
        assertImplements<ForegroundSyncController, ForegroundSyncGateway>()
        assertImplements<HealthDataRefreshAdapter, HealthDataRefresh>()
        assertImplements<HistoricalResyncControllerImpl, HistoricalResyncController>()
    }

    private inline fun <reified Implementation : Any, reified Contract : Any> assertImplements() {
        assertTrue(
            Contract::class.java.isAssignableFrom(Implementation::class.java),
            "${Implementation::class.java.name} must implement ${Contract::class.java.name}",
        )
    }
}
