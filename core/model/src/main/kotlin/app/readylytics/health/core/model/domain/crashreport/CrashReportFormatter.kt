package app.readylytics.health.core.model.domain.crashreport

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
        append(throwable.stackTraceToString())
    }
