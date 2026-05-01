package com.gregor.lauritz.healthdashboard.data.preferences

import kotlinx.coroutines.flow.first
import javax.inject.Inject

class DataStoreCircadianThresholdPreferences
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : CircadianThresholdPreferences {
    override val overrideMinutes: Int?
        get() = null // TODO: Add circadianThresholdOverride field to UserPreferences after initial deployment

    override suspend fun setOverride(minutes: Int?) {
        if (minutes != null) {
            userPreferencesRepository.updateConsistencyThresholdMinutes(minutes)
        }
    }
}
