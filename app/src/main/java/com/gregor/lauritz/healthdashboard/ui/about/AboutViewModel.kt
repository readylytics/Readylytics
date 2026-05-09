package com.gregor.lauritz.healthdashboard.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    fun dismissAbout(onComplete: () -> Unit) {
        viewModelScope.launch {
            settingsRepo.updateAboutDismissed(true)
            onComplete()
        }
    }
}
