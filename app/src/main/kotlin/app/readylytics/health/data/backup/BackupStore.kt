package app.readylytics.health.data.backup

import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupLocation
import java.io.File
import java.io.InputStream

interface BackupStore {
    suspend fun list(): List<BackupFileInfo>

    suspend fun read(location: BackupLocation): InputStream

    /** Publishes [source] as [name], replacing any existing entry atomically. */
    suspend fun publish(
        source: File,
        name: String,
    )

    suspend fun delete(location: BackupLocation)

    suspend fun prune(retentionPeriodMs: Long)
}
