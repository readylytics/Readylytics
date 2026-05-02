package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.util.SecureLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreCircadianThresholdPreferences
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val encryptionManager: EncryptionManager,
    ) : CircadianThresholdPreferences {
    
    override val overrideMinutesFlow: Flow<Int?> by lazy {
        userPreferencesRepository.userPreferences.map { prefs ->
            prefs.circadianThresholdOverride?.let { encrypted ->
                runCatching { 
                    encryptionManager.decrypt(encrypted).toInt() 
                }.onFailure { e ->
                    SecureLogger.error("Failed to decrypt circadian threshold override", e)
                    // If decryption fails, the key might be invalid or rotated.
                    // We clear the override to allow the app to recover to defaults.
                    userPreferencesRepository.updateCircadianThresholdOverride(null)
                }.getOrNull()
            }
        }
    }

    override suspend fun setOverride(minutes: Int?) {
        val encrypted = minutes?.let { encryptionManager.encrypt(it.toString()) }
        userPreferencesRepository.updateCircadianThresholdOverride(encrypted)
    }

    override val isEncrypted: Boolean = true
}
