package app.readylytics.health.ui.crashreport

import androidx.lifecycle.ViewModel
import app.readylytics.health.core.model.domain.crashreport.CrashReportStore
import app.readylytics.health.core.model.domain.crashreport.RecalcDiagnosticStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

/** Which stored report the startup prompt offers. A crash report always takes priority. */
enum class StartupReportKind {
    CRASH,
    RECALC_DIAGNOSTIC,
}

/**
 * [hasReport], [reportFile], [reportText], [consumeReport] and [clearReport] always refer to the
 * crash report (the Settings issue-report flow attaches it). The startup prompt instead works on
 * [promptKind] -- the crash report if one exists, otherwise a pending large-recalculation
 * diagnostic -- via the `prompt*` members.
 */
@HiltViewModel
class CrashReportViewModel
    @Inject
    constructor(
        private val crashReportStore: CrashReportStore,
        private val recalcDiagnosticStore: RecalcDiagnosticStore? = null,
    ) : ViewModel() {
        private val _promptKind = MutableStateFlow(resolvePromptKind())
        val promptKind: StateFlow<StartupReportKind?> = _promptKind.asStateFlow()

        private val _showPrompt = MutableStateFlow(_promptKind.value != null)
        val showPrompt: StateFlow<Boolean> = _showPrompt.asStateFlow()

        private val _hasReport = MutableStateFlow(crashReportStore.hasReport())
        val hasReport: StateFlow<Boolean> = _hasReport.asStateFlow()

        fun reportFile(): File = crashReportStore.reportFile()

        fun reportText(): String = crashReportStore.read().orEmpty()

        fun promptReportFile(): File =
            when (_promptKind.value) {
                StartupReportKind.RECALC_DIAGNOSTIC -> recalcDiagnosticStore?.reportFile() ?: reportFile()
                else -> reportFile()
            }

        fun promptReportText(): String =
            when (_promptKind.value) {
                StartupReportKind.RECALC_DIAGNOSTIC -> recalcDiagnosticStore?.read().orEmpty()
                else -> reportText()
            }

        fun dismiss() {
            _showPrompt.value = false
        }

        fun consumeReport() = hideAndDeleteReport()

        fun clearReport() = hideAndDeleteReport()

        /** Deletes whichever report the prompt is showing and hides the prompt. */
        fun consumePromptReport() {
            when (_promptKind.value) {
                StartupReportKind.RECALC_DIAGNOSTIC -> recalcDiagnosticStore?.delete()
                StartupReportKind.CRASH -> {
                    crashReportStore.delete()
                    _hasReport.value = false
                }
                null -> Unit
            }
            _showPrompt.value = false
        }

        private fun hideAndDeleteReport() {
            crashReportStore.delete()
            _showPrompt.value = false
            _hasReport.value = false
        }

        private fun resolvePromptKind(): StartupReportKind? =
            when {
                crashReportStore.hasReport() -> StartupReportKind.CRASH
                recalcDiagnosticStore?.hasReport() == true -> StartupReportKind.RECALC_DIAGNOSTIC
                else -> null
            }
    }
