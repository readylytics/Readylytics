package app.readylytics.health.core.model.domain.crashreport

import app.readylytics.health.core.model.domain.util.DiagnosticReason
import app.readylytics.health.core.model.domain.util.safeDiagnostic

fun formatCrashReport(
    throwable: Throwable,
    metadata: CrashReportMetadata,
): String =
    buildString {
        appendLine("Readylytics crash report")
        appendLine("Time: ${metadata.timestampIso}")
        appendLine("App version: ${metadata.appVersionName} (${metadata.appVersionCode})")
        appendLine("Android: ${metadata.androidRelease} (SDK ${metadata.androidSdkInt})")
        appendLine("Device: ${metadata.deviceManufacturer} ${metadata.deviceModel}")
        appendLine()
        append(safeDiagnostic(DiagnosticReason.OPERATION_FAILED, throwable))
    }
