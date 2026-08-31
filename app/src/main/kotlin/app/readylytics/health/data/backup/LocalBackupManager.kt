package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.backup.BackupFileInfo
import app.readylytics.health.core.model.domain.backup.BackupLocation
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val backupStreamWriter: BackupStreamWriter,
        private val encryptionManager: EncryptionManager,
        private val auditTrailRepository: AuditTrailRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val backupStoreFactory: BackupStoreFactory = DefaultBackupStoreFactory(context),
    ) {
        private val defaultBackupDir = File(context.filesDir, "backups")

        suspend fun createBackup(): Result<File?> =
            withContext(ioDispatcher) {
                var tempJsonFile: File? = null
                var tempZipFile: File? = null
                try {
                    val prefs = settingsRepository.userPreferences.first()
                    val customUri = prefs.backupDirectoryUri?.toUri()

                    // Prune old backups from both internal and custom locations
                    pruneOldBackups(customUri)

                    val timestamp =
                        Instant.now().atZone(ZoneId.systemDefault()).format(FILENAME_FORMATTER)
                    val jsonFilename = "backup_$timestamp.json"
                    val zipFilename = "backup_$timestamp.zip"

                    // 1. Write JSON to a temporary file
                    val jsonFile = File(context.cacheDir, jsonFilename)
                    tempJsonFile = jsonFile
                    FileOutputStream(jsonFile).use { fos ->
                        backupStreamWriter.writeJsonStreaming(fos)
                    }

                    // 2. Fetch and decrypt backup password
                    val password =
                        prefs.backupPasswordHash?.let { hash ->
                            encryptionManager.decrypt(hash)
                        } ?: error("Backup password not set")

                    // 3. Create ZIP file
                    tempZipFile = File(context.cacheDir, zipFilename)
                    createZip(jsonFile, tempZipFile, password)

                    val store = backupStoreFactory.create(customUri)
                    store.publish(tempZipFile, zipFilename)
                    val finalFile = if (customUri != null) null else File(defaultBackupDir, zipFilename)

                    auditTrailRepository.appendBestEffort(
                        "LocalBackupManager",
                        AuditEvent(
                            type = AuditEvent.Type.BACKUP_CREATED,
                            occurredAt = Instant.now(),
                            detail = null,
                        ),
                    )
                    Result.success(finalFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The UI turns this into a bare "Backup failed"; without a log the cause
                    // never reaches logcat and the failure is undiagnosable on a real device.
                    logE("LocalBackupManager", e) { "createBackup failed" }
                    Result.failure(e)
                } finally {
                    tempJsonFile?.delete()
                    tempZipFile?.delete()
                }
            }

        suspend fun deleteBackup(uri: Uri): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    val store = backupStoreFactory.create(uri)
                    store.delete(BackupLocation(uri.toString()))
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logE("LocalBackupManager", e) { "deleteBackup failed for $uri" }
                    Result.failure(e)
                }
            }

        suspend fun reencryptBackups(
            oldPassword: String?,
            newPassword: String?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    val prefs = settingsRepository.userPreferences.first()
                    val customUri = prefs.backupDirectoryUri?.toUri()
                    val store = backupStoreFactory.create(customUri)
                    val backups = store.list()
                    val tempDir = File(context.cacheDir, "reencrypt_temp")
                    tempDir.mkdirs()

                    try {
                        backups.forEach { info ->
                            reencryptOneBackup(store, info, tempDir, oldPassword, newPassword)
                        }
                    } finally {
                        tempDir.deleteRecursively()
                    }
                    auditTrailRepository.appendBestEffort(
                        "LocalBackupManager",
                        AuditEvent(
                            type = AuditEvent.Type.KEY_ROTATED,
                            occurredAt = Instant.now(),
                            detail = null,
                        ),
                    )
                    Result.success(Unit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The audit trail records only the exception class name; the message and
                    // stack are what actually identify a provider-specific SAF failure.
                    logE("LocalBackupManager", e) { "reencryptBackups failed" }
                    auditTrailRepository.appendBestEffort(
                        "LocalBackupManager",
                        AuditEvent(
                            type = AuditEvent.Type.KEY_ROTATION_FAILED,
                            occurredAt = Instant.now(),
                            detail = e::class.simpleName,
                        ),
                    )
                    Result.failure(e)
                }
            }

        private suspend fun reencryptOneBackup(
            store: BackupStore,
            info: BackupFileInfo,
            tempDir: File,
            oldPassword: String?,
            newPassword: String?,
        ) {
            val tempZip = File(tempDir, info.name)
            val newZipPath = File(tempDir, "reencrypt_new_${System.currentTimeMillis()}.zip")

            // 1. Copy source to temp zip
            store.read(info.location).use { input ->
                tempZip.outputStream().use { output -> input.copyTo(output) }
            }

            // 2. Stream entries directly to new zip with new password (no plaintext JSON on disk)
            ZipFile(tempZip, oldPassword?.toCharArray()).use { source ->
                streamZipEntries(source, newZipPath, newPassword)
            }

            // 3. Publish re-encrypted archive atomically
            store.publish(newZipPath, info.name)

            // 4. Cleanup temp zip files
            tempZip.delete()
            newZipPath.delete()
        }

        private fun streamZipEntries(
            source: ZipFile,
            newZipPath: File,
            newPassword: String?,
        ) {
            net.lingala.zip4j.io.outputstream
                .ZipOutputStream(
                    newZipPath.outputStream(),
                    newPassword?.toCharArray(),
                ).use { sink ->
                    writeSourceEntriesToSink(source, sink, newPassword)
                }
        }

        private fun writeSourceEntriesToSink(
            source: ZipFile,
            sink: net.lingala.zip4j.io.outputstream.ZipOutputStream,
            newPassword: String?,
        ) {
            source.fileHeaders.forEach { header ->
                val params =
                    ZipParameters().apply {
                        fileNameInZip = header.fileName
                        if (newPassword != null) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                sink.putNextEntry(params)
                source.getInputStream(header).use { it.copyTo(sink) }
                sink.closeEntry()
            }
        }

        private fun createZip(
            inputFile: File,
            zipFile: File,
            password: String?,
        ) {
            ZipFile(zipFile, password?.toCharArray()).use { zip ->
                val parameters =
                    ZipParameters().apply {
                        if (password != null) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                zip.addFile(inputFile, parameters)
            }
        }

        suspend fun listBackups(): List<BackupFileInfo> =
            withContext(ioDispatcher) {
                val prefs = settingsRepository.userPreferences.first()
                val customUri = prefs.backupDirectoryUri?.toUri()
                val store = backupStoreFactory.create(customUri)
                store.list()
            }

        private suspend fun pruneOldBackups(customUri: Uri?) {
            backupStoreFactory.createDefault().prune(RETENTION_PERIOD_MS)
            if (customUri != null) {
                backupStoreFactory.create(customUri).prune(RETENTION_PERIOD_MS)
            }
        }

        companion object {
            private val FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            private const val RETENTION_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
        }
    }
