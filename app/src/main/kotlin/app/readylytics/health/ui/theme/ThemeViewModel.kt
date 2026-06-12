package app.readylytics.health.ui.theme

import androidx.lifecycle.ViewModel
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        val dynamicColorFlow: Flow<Boolean> = settingsRepo.dynamicColorEnabled
        val fallbackThemeColorFlow: Flow<FallbackThemeColor> = settingsRepo.fallbackThemeColor
        val isCustomPaletteEnabledFlow: Flow<Boolean> = settingsRepo.isCustomPaletteEnabled
        val customSecondaryColorFlow: Flow<Long> = settingsRepo.customSecondaryColor
        val customTertiaryColorFlow: Flow<Long> = settingsRepo.customTertiaryColor
        val customPrimaryColorFlow: Flow<Long> = settingsRepo.customPrimaryColor
    }
