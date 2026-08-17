package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupLocation
import java.io.File
import java.io.InputStream

class SafBackupStore(
    private val context: Context,
    private val treeUri: Uri,
) : BackupStore {
    private fun getTreeDocumentFile(): DocumentFile? =
        if (treeUri.scheme == "file") {
            treeUri.path?.let { DocumentFile.fromFile(File(it)) }
        } else {
            DocumentFile.fromTreeUri(context, treeUri)
        }

    override suspend fun list(): List<BackupFileInfo> {
        val dir = getTreeDocumentFile()
        return dir
            ?.listFiles()
            ?.filter { it.isFile && it.name?.startsWith("backup_") == true && it.name?.endsWith(".zip") == true }
            ?.map {
                BackupFileInfo(
                    name = it.name ?: "",
                    lastModified = it.lastModified(),
                    sizeBytes = it.length(),
                    location = BackupLocation(it.uri.toString()),
                )
            }?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    override suspend fun read(location: BackupLocation): InputStream {
        val uri = location.value.toUri()
        if (uri.scheme == "file") {
            val file = File(uri.path ?: error("Invalid file location"))
            return file.inputStream()
        }
        return context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not read backup")
    }

    override suspend fun publish(
        source: File,
        name: String,
    ) {
        check(source.length() > 0) { "Cannot publish empty backup" }
        val dir =
            getTreeDocumentFile()
                ?: error("Could not access backup directory")
        val staged =
            dir.createFile("application/zip", "$name.rotating")
                ?: error("Could not stage re-encrypted backup")
        val writeSuccess =
            try {
                context.contentResolver.openOutputStream(staged.uri)?.use { output ->
                    source.inputStream().use { it.copyTo(output) }
                } != null
            } catch (e: Exception) {
                staged.delete()
                throw e
            }
        if (!writeSuccess) {
            staged.delete()
            error("Could not write re-encrypted backup")
        }

        check(staged.length() == source.length()) {
            staged.delete()
            "Staged backup is truncated (${staged.length()} vs ${source.length()}); original kept"
        }
        val existing = dir.findFile(name)
        if (existing != null) {
            val backupName = "$name.bak"
            dir.findFile(backupName)?.delete()
            check(existing.renameTo(backupName)) {
                staged.delete()
                "Could not backup existing file before replacement"
            }
            if (!staged.renameTo(name)) {
                existing.renameTo(name)
                staged.delete()
                error("Could not finalize re-encrypted backup")
            }
            existing.delete()
        } else {
            check(staged.renameTo(name)) {
                staged.delete()
                "Could not finalize re-encrypted backup"
            }
        }
    }

    override suspend fun delete(location: BackupLocation) {
        val uri = location.value.toUri()
        if (uri.scheme == "file") {
            val file = File(uri.path ?: error("Invalid file location"))
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Failed to delete SAF document")
            }
            return
        }
        val documentFile = DocumentFile.fromSingleUri(context, uri)
        if (documentFile?.delete() == false) {
            val root = getTreeDocumentFile()
            val file = root?.findFile(documentFile.name ?: "")
            if (file?.delete() == false) {
                throw IllegalStateException("Failed to delete SAF document")
            }
        }
    }

    override suspend fun prune(retentionPeriodMs: Long) {
        val now = System.currentTimeMillis()
        val treeDir = getTreeDocumentFile()
        treeDir
            ?.listFiles()
            ?.filter {
                it.isFile && it.name?.startsWith("backup_") == true && it.name?.endsWith(".zip") == true
            }?.filter { now - it.lastModified() > retentionPeriodMs }
            ?.forEach { it.delete() }
    }
}
