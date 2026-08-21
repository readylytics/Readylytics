package app.readylytics.health

import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Pulls the first user-preferences emission off the first-frame critical path.
 *
 * MainActivity's splash keep-condition blocks the first frame until userPreferences emits, and
 * that read is otherwise triggered only by composition -- serializing the proto-store load (and
 * its one-time DataMigrations) after Activity startup instead of overlapping it. DataStore caches
 * after the first read, so the Activity's collection then resolves immediately.
 *
 * Deliberately NOT part of DatabaseReadyStartupInitializer: that work waits for
 * DatabaseReadiness.Ready, and gating the pre-warm on the DB migration would defeat its purpose.
 */
internal class PreferencesPrewarmer(
    private val settingsRepository: Lazy<SettingsRepository>,
) {
    suspend fun prewarm() {
        try {
            settingsRepository.get().userPreferences.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fire-and-forget: the Activity's own collection is the authoritative read, and the
            // splash timeout already bounds a stalled store. A failure here must not crash startup.
            logE(TAG, e) { "User preferences pre-warm failed" }
        }
    }

    private companion object {
        const val TAG = "HealthDashboardApplication"
    }
}
