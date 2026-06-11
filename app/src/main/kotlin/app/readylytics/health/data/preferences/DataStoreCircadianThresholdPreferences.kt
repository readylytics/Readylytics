package app.readylytics.health.data.preferences

import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.util.SecureLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreCircadianThresholdPreferences
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val encryptionManager: EncryptionManager,
    ) : CircadianThresholdPreferences {
        override val overrideMinutesFlow: Flow<Int?> by lazy {
            settingsRepo.userPreferences.map { prefs ->
                prefs.circadianThresholdOverride?.let { encrypted ->
                    runCatching {
                        encryptionManager.decrypt(encrypted)?.toInt()
                    }.onFailure { e ->
                        SecureLogger.error("Failed to decrypt circadian threshold override", e)
                        // If decryption fails, the key might be invalid or rotated.
                        // We clear the override to allow the app to recover to defaults.
                        settingsRepo.updateCircadianThresholdOverride(null)
                    }.getOrNull()
                }
            }
        }

        override suspend fun setOverride(minutes: Int?) {
            val encrypted = minutes?.let { encryptionManager.encrypt(it.toString()) }
            settingsRepo.updateCircadianThresholdOverride(encrypted)
        }

        override val isEncrypted: Boolean = true
    }
