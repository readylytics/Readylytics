package app.readylytics.health.core.model.domain.crashreport

import java.io.File

/**
 * Local log of large, unexpected background recalculations (see [RecalcDiagnostic]). Offered for
 * sharing through the same startup dialog as crash reports; nothing leaves the device unless the
 * user chooses to send it.
 */
interface RecalcDiagnosticStore {
    fun hasReport(): Boolean

    fun append(entry: String)

    fun read(): String?

    fun delete()

    fun reportFile(): File
}
