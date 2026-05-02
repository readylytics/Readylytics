package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
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
                runCatching { encryptionManager.decrypt(encrypted).toInt() }.getOrNull()
            }
        }
    }

    override suspend fun setOverride(minutes: Int?) {
        val encrypted = minutes?.let { encryptionManager.encrypt(it.toString()) }
        userPreferencesRepository.updateCircadianThresholdOverride(encrypted)
    }

    override val isEncrypted: Boolean = true
}
