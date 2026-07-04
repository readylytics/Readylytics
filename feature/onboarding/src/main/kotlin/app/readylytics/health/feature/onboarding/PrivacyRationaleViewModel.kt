package app.readylytics.health.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.domain.preferences.UserPreferencesReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PrivacyRationaleViewModel
    @Inject
    constructor(
        settingsRepository: UserPreferencesReader,
    ) : ViewModel() {
        val appTheme =
            settingsRepository.userPreferences
                .map { it.appTheme }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppTheme.SYSTEM)
    }
