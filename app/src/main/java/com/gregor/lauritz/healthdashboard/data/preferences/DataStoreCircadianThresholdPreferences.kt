package com.gregor.lauritz.healthdashboard.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreCircadianThresholdPreferences
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : CircadianThresholdPreferences {
    override val overrideMinutesFlow: Flow<Int?>
        get() = userPreferencesRepository.userPreferences.map { it.circadianThresholdOverride }

    override suspend fun setOverride(minutes: Int?) {
        userPreferencesRepository.updateCircadianThresholdOverride(minutes)
    }
}
