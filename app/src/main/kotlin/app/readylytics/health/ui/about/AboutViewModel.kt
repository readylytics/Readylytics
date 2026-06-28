package app.readylytics.health.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.preferences.AboutPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AboutViewModel
    @Inject
    constructor(
        private val aboutPreferences: AboutPreferences,
    ) : ViewModel() {
        fun dismissAbout(onComplete: () -> Unit) {
            viewModelScope.launch {
                aboutPreferences.updateAboutDismissed(true)
                onComplete()
            }
        }
    }
