package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthState
import com.gregor.lauritz.healthdashboard.data.drive.GoogleDriveRepository
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.security.BackupEncryptionHelper
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.data.security.SqlCipherKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupUseCase
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val driveAuthManager: DriveAuthManager,
        private val driveRepository: GoogleDriveRepository,
        private val healthDatabase: HealthDatabase,
        private val settingsRepo: SettingsRepository,
        private val sqlCipherKeyManager: SqlCipherKeyManager,
        private val encryptionManager: EncryptionManager,
        private val backupEncryptionHelper: BackupEncryptionHelper,
    ) {
        suspend fun execute(): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val authState = driveAuthManager.observeAuthState().first()
                    if (authState !is DriveAuthState.SignedIn) {
                        throw IllegalStateException("Not signed in to Google Drive")
                    }
                    val accessToken =
                        driveAuthManager
                            .getAccessToken(context)
                            .getOrThrow()

                    val tempDir = File(context.cacheDir, "backup_temp").also { it.mkdirs() }
                    val zipFile = File(context.cacheDir, "health_backup.zip")
                    val encryptedZipFile = File(context.cacheDir, "health_backup.zip.enc")
                    try {
                        val prefs = settingsRepo.userPreferences.first()
                        val dbFile = context.getDatabasePath("health_dashboard.db")
                        // Export decrypted version for backup — ensures usability on new devices
                        sqlCipherKeyManager.exportPlaintext(dbFile, File(tempDir, "health.db"))

                        val prefsJson = exportPreferencesToJson()
                        File(tempDir, "preferences.json").writeText(prefsJson)

                        zipDirectory(tempDir, zipFile)

                        // Validate the ZIP before encryption — guard against corrupt archives
                        require(zipFile.exists() && zipFile.length() > 0) { "Backup ZIP creation failed" }
                        ZipFile(zipFile).use { /* validates ZIP structure */ }

                        // Encrypt the ZIP before upload
                        val backupPassword =
                            prefs.backupPassword?.let {
                                encryptionManager.decrypt(it)
                            }

                        if (backupPassword != null) {
                            val ciphertext = backupEncryptionHelper.encrypt(zipFile.readBytes(), backupPassword)
                            encryptedZipFile.writeBytes(ciphertext)
                        } else {
                            // Legacy fallback: use Keystore encryption if no password is set
                            val ciphertext = encryptionManager.encrypt(zipFile.readBytes())
                            encryptedZipFile.writeBytes(ciphertext)
                        }

                        // Upload first so an upload failure keeps the previous backup intact
                        val uploadedId = driveRepository.uploadBackup(accessToken, encryptedZipFile)
                        settingsRepo.updateLastBackupTimestamp(System.currentTimeMillis())

                        // Prune old backups, keeping the one we just uploaded plus one previous version
                        val existing =
                            driveRepository
                                .listBackupFiles(accessToken)
                                .filter { it.id != uploadedId }
                                .sortedByDescending { it.name }
                        existing.drop(1).forEach { driveRepository.deleteFile(accessToken, it.id) }
                    } finally {
                        tempDir.deleteRecursively()
                        zipFile.delete()
                        encryptedZipFile.delete()
                    }
                    Unit
                }
            }

        private suspend fun exportPreferencesToJson(): String {
            val prefs = settingsRepo.userPreferences.first()
            val json = JSONObject()
            json.put("goalSleepHours", prefs.goalSleepHours)
            json.put("hrvBaselineOverride", prefs.hrvBaselineOverride ?: JSONObject.NULL)
            json.put("rhrBaselineOverride", prefs.rhrBaselineOverride ?: JSONObject.NULL)
            json.put("syncPreference", prefs.syncPreference.name)
            json.put("syncIntervalHours", prefs.syncIntervalHours)
            json.put("maxHeartRate", prefs.maxHeartRate)
            json.put("hrvOptimalThreshold", prefs.hrvOptimalThreshold)
            json.put("hrvWarningThreshold", prefs.hrvWarningThreshold)
            json.put("rhrOptimalThreshold", prefs.rhrOptimalThreshold)
            json.put("rhrWarningThreshold", prefs.rhrWarningThreshold)
            json.put("restingHrBeforeMinutes", prefs.restingHrBeforeMinutes)
            json.put("restingHrAfterMinutes", prefs.restingHrAfterMinutes)
            json.put("appTheme", prefs.appTheme.name)
            json.put("backupSchedule", prefs.backupSchedule.name)
            json.put("birthDay", prefs.birthDay)
            json.put("birthMonth", prefs.birthMonth)
            json.put("birthYear", prefs.birthYear)
            return json.toString()
        }

        private fun zipDirectory(
            sourceDir: File,
            destZip: File,
        ) {
            ZipOutputStream(destZip.outputStream().buffered()).use { zos ->
                sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
