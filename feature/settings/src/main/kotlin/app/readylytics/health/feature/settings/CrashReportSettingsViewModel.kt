package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import app.readylytics.health.domain.crashreport.CrashReportStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class CrashReportSettingsViewModel
    @Inject
    constructor(
        private val crashReportStore: CrashReportStore,
    ) : ViewModel() {
        private val _hasCrashReport = MutableStateFlow(crashReportStore.hasReport())
        val hasCrashReport: StateFlow<Boolean> = _hasCrashReport.asStateFlow()

        fun markSent() {
            crashReportStore.delete()
            _hasCrashReport.value = false
        }
    }
