package app.readylytics.health.data.local

import app.readylytics.health.domain.repository.WalDiagnostics
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [WalDiagnostics]: a pure file-size stat of the `-wal` sidecar rather than a
 * `PRAGMA wal_checkpoint` -- a checkpoint is a real DB side effect (it can truncate the WAL file)
 * and would alter the very growth this instrumentation exists to measure.
 */
@Singleton
class RoomWalDiagnostics
    @Inject
    constructor(
        private val database: HealthDatabase,
    ) : WalDiagnostics {
        override fun walFileSizeInfo(): String =
            try {
                val dbPath = database.openHelper.writableDatabase.path
                if (dbPath == null) {
                    "unavailable"
                } else {
                    val walFile = File("$dbPath-wal")
                    if (walFile.exists()) "${walFile.length()} bytes" else "0 bytes"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "error: ${e.message}"
            }
    }
