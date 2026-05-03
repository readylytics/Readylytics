package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.sync.ResyncHealthConnectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val healthSyncUseCase: HealthSyncUseCase,
    private val resyncHealthConnectUseCase: ResyncHealthConnectUseCase,
) : ViewModel() {

    private val _isResyncing = MutableStateFlow(false)

    // Internal property to allow overriding in tests
    var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

    val uiState: StateFlow<SyncSettingsState> by lazy {
        combine(
            prefsRepo.userPreferences,
            _isResyncing
        ) { prefs, isResyncing ->
            SyncSettingsState(
                syncPreference = prefs.syncPreference,
                syncIntervalHours = prefs.syncIntervalHours,
                isResyncing = isResyncing
            )
        }.stateIn(
            scope = viewModelScope,
            started = sharingStarted,
            initialValue = SyncSettingsState()
        )
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SyncPreferenceChanged -> {
                viewModelScope.launch {
                    prefsRepo.updateSyncPreference(pref = event.pref)
                    if (event.pref == SyncPreference.ALWAYS) {
                        healthSyncUseCase.sync()
                    }
                }
            }
            is SettingsEvent.SyncIntervalChanged -> {
                viewModelScope.launch {
                    prefsRepo.updateSyncIntervalHours(hours = event.hours)
                }
            }
            SettingsEvent.ResyncHealthConnect -> {
                viewModelScope.launch {
                    _isResyncing.value = true
                    resyncHealthConnectUseCase.execute()
                    _isResyncing.value = false
                }
            }
            else -> {}
        }
    }
}
