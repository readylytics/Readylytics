package app.readylytics.health.ui.crashreport

import androidx.lifecycle.ViewModel
import app.readylytics.health.domain.crashreport.CrashReportStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CrashReportViewModel
    @Inject
    constructor(
        private val crashReportStore: CrashReportStore,
    ) : ViewModel() {
        private val _showPrompt = MutableStateFlow(crashReportStore.hasReport())
        val showPrompt: StateFlow<Boolean> = _showPrompt.asStateFlow()

        fun reportFile(): File = crashReportStore.reportFile()

        fun reportText(): String = crashReportStore.read().orEmpty()

        fun dismiss() {
            _showPrompt.value = false
        }

        fun consumeReport() {
            crashReportStore.delete()
            _showPrompt.value = false
        }
    }
