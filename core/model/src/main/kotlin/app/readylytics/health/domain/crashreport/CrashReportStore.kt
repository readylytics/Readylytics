package app.readylytics.health.domain.crashreport

import java.io.File

interface CrashReportStore {
    fun hasReport(): Boolean

    fun write(report: String)

    fun read(): String?

    fun delete()

    fun reportFile(): File
}
