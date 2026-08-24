package app.readylytics.health.core.model.domain.crashreport

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
