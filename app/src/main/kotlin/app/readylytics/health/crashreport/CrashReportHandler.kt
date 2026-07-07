package app.readylytics.health.crashreport

import android.content.Context
import android.os.Build
import app.readylytics.health.BuildConfig
import app.readylytics.health.data.crashreport.CrashReportStoreImpl
import app.readylytics.health.domain.crashreport.CrashReportMetadata
import app.readylytics.health.domain.crashreport.formatCrashReport
import java.time.Instant

/**
 * Persists a diagnostic-only report to disk before delegating to the previous handler, so the
 * normal OS crash behavior (dialog, process death) is unaffected. Runs synchronously with plain
 * file I/O only - the process is about to die, so no coroutines/dispatchers are used.
 */
class CrashReportHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        runCatching {
            val metadata =
                CrashReportMetadata(
                    timestampIso = Instant.now().toString(),
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    androidRelease = Build.VERSION.RELEASE,
                    androidSdkInt = Build.VERSION.SDK_INT,
                    deviceManufacturer = Build.MANUFACTURER,
                    deviceModel = Build.MODEL,
                )
            CrashReportStoreImpl(context).write(formatCrashReport(throwable, metadata))
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
