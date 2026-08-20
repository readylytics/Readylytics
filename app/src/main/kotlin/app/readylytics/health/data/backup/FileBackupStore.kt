package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.core.model.domain.backup.BackupFileInfo
import app.readylytics.health.core.model.domain.backup.BackupLocation
import java.io.File
import java.io.InputStream

class FileBackupStore(
    private val context: Context,
    private val backupDir: File = File(context.filesDir, "backups"),
) : BackupStore {
    override suspend fun list(): List<BackupFileInfo> {
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
        return backupDir
            .listFiles { f -> f.name.startsWith("backup_") && f.name.endsWith(".zip") && f.isFile }
            ?.map {
                BackupFileInfo(
                    name = it.name,
                    lastModified = it.lastModified(),
                    sizeBytes = it.length(),
                    location = BackupLocation(Uri.fromFile(it).toString()),
                )
            }?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    override suspend fun read(location: BackupLocation): InputStream {
        val file = File(location.value.toUri().path ?: error("Invalid file location"))
        return file.inputStream()
    }

    override suspend fun publish(
        source: File,
        name: String,
    ) {
        backupDir.mkdirs()
        val target = File(backupDir, name)
        check(source.length() > 0) { "Cannot publish empty backup" }
        if (source.renameTo(target)) {
            return
        }
        val tempTarget = File(backupDir, "$name.tmp")
        try {
            source.copyTo(tempTarget, overwrite = true)
            check(tempTarget.length() == source.length()) {
                "Failed to copy complete backup to temporary file (${tempTarget.length()} vs ${source.length()})"
            }
            check(tempTarget.renameTo(target)) {
                "Could not replace $target with rotated backup; original left in place"
            }
            source.delete()
        } finally {
            if (tempTarget.exists()) {
                tempTarget.delete()
            }
        }
    }

    override suspend fun delete(location: BackupLocation) {
        val file = File(location.value.toUri().path ?: error("Invalid file location"))
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Failed to delete local file")
        }
    }

    override suspend fun prune(retentionPeriodMs: Long) {
        val now = System.currentTimeMillis()
        if (backupDir.exists()) {
            backupDir
                .listFiles { f ->
                    f.name.startsWith("backup_") && f.name.endsWith(".zip") && f.isFile
                }?.filter { now - it.lastModified() > retentionPeriodMs }
                ?.forEach { it.delete() }
        }
    }
}
