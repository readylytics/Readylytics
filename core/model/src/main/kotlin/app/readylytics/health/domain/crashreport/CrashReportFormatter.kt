package app.readylytics.health.domain.crashreport

/**
 * Device/app diagnostics only - never include health data, matching the SecureLogger policy
 * of keeping raw health values out of anything that can leave the device.
 */
data class CrashReportMetadata(
    val timestampIso: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidSdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
)

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
