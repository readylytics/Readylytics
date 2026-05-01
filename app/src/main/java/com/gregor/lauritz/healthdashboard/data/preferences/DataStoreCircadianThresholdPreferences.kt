package com.gregor.lauritz.healthdashboard.data.preferences

import kotlinx.coroutines.flow.first
import javax.inject.Inject

class DataStoreCircadianThresholdPreferences
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : CircadianThresholdPreferences {
    override val overrideMinutes: Int?
        get() {
            // Note: This is a blocking call - should ideally be a Flow for reactive UI
            // TODO: Convert to suspend function or Flow if UI needs reactive updates
            return try {
                // For now, return null and rely on ScoringConfigFactory to read preferences directly
                null
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun setOverride(minutes: Int?) {
        userPreferencesRepository.updateCircadianThresholdOverride(minutes)
    }
}
