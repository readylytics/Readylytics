package app.readylytics.health.data.crashreport

import android.content.Context
import app.readylytics.health.core.model.domain.crashreport.RecalcDiagnosticStore
import app.readylytics.health.core.model.domain.crashreport.appendRecalcDiagnosticEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecalcDiagnosticStoreImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RecalcDiagnosticStore {
        private val lock = Any()

        override fun hasReport(): Boolean = reportFile().exists()

        override fun append(entry: String) {
            synchronized(lock) {
                val file = reportFile()
                file.parentFile?.mkdirs()
                file.writeText(appendRecalcDiagnosticEntry(read(), entry))
            }
        }

        override fun read(): String? = reportFile().takeIf { it.exists() }?.readText()

        override fun delete() {
            synchronized(lock) { reportFile().delete() }
        }

        override fun reportFile(): File = File(File(context.cacheDir, RECALC_DIAGNOSTICS_DIR), RECALC_DIAGNOSTICS_FILE)

        companion object {
            const val RECALC_DIAGNOSTICS_DIR = "recalc_diagnostics"
            const val RECALC_DIAGNOSTICS_FILE = "recalc_diagnostics.txt"
        }
    }
