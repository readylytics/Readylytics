package app.readylytics.health.data.crashreport

import android.content.Context
import app.readylytics.health.domain.crashreport.CrashReportStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashReportStoreImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CrashReportStore {
        override fun hasReport(): Boolean = reportFile().exists()

        override fun write(report: String) {
            val file = reportFile()
            file.parentFile?.mkdirs()
            file.writeText(report)
        }

        override fun read(): String? = reportFile().takeIf { it.exists() }?.readText()

        override fun delete() {
            reportFile().delete()
        }

        override fun reportFile(): File = File(File(context.cacheDir, CRASH_REPORTS_DIR), LATEST_CRASH_FILE)

        companion object {
            const val CRASH_REPORTS_DIR = "crash_reports"
            const val LATEST_CRASH_FILE = "latest_crash.txt"
        }
    }
