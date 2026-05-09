package com.gregor.lauritz.healthdashboard.ui.theme

import androidx.lifecycle.ViewModel
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository
) : ViewModel() {
    val dynamicColorFlow: Flow<Boolean> = settingsRepo.dynamicColorEnabled
}
