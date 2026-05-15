package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthState
import com.gregor.lauritz.healthdashboard.data.drive.GoogleDriveRepository
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.security.BackupEncryptionHelper
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.data.security.SqlCipherKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestoreUseCase
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
        sealed class RestoreResult {
            data object Success : RestoreResult()

            data object SuccessRequiresRestart : RestoreResult()

            data class Failure(
                val cause: Throwable,
            ) : RestoreResult()
        }

        // Phase 1: download and validate — call this before showing the confirmation dialog.
        suspend fun downloadAndValidate(): Result<File> =
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

                    val files = driveRepository.listBackupFiles(accessToken)
                    val backupFile =
                        files.firstOrNull { it.name == "health_backup.zip" }
                            ?: throw IllegalStateException("No backup found in Google Drive")

                    val encryptedZip = File(context.cacheDir, "restore_temp.zip.enc")
                    val zipDest = File(context.cacheDir, "restore_temp.zip")
                    driveRepository.downloadBackup(accessToken, backupFile.id, encryptedZip)

                    // Decrypt the ZIP
                    val prefs = settingsRepo.userPreferences.first()
                    val backupPassword =
                        prefs.backupPassword?.let {
                            encryptionManager.decrypt(it)
                        }

                    val ciphertext = encryptedZip.readBytes()
                    val plaintext =
                        if (backupPassword != null) {
                            try {
                                backupEncryptionHelper.decrypt(ciphertext, backupPassword)
                            } catch (e: Exception) {
                                // If password decryption fails, try Keystore (legacy)
                                encryptionManager.decrypt(ciphertext)
                            }
                        } else {
                            encryptionManager.decrypt(ciphertext)
                        }
                    zipDest.writeBytes(plaintext)
                    encryptedZip.delete()

                    val unzipDir =
                        File(context.cacheDir, "restore_unzipped").also {
                            it.deleteRecursively()
                            it.mkdirs()
                        }
                    unzip(zipDest, unzipDir)
                    zipDest.delete()

                    require(File(unzipDir, "health.db").exists()) { "Backup is missing health.db" }
                    require(File(unzipDir, "preferences.json").exists()) { "Backup is missing preferences.json" }

                    unzipDir
                }
            }

        // Phase 2: apply the restore — call this after the user confirms.
        // Replaces all local data and returns a result indicating if restart is needed.
        suspend fun applyRestore(unzipDir: File): RestoreResult =
            withContext(Dispatchers.IO) {
                runCatching {
                    val dbDest = context.getDatabasePath("health_dashboard.db")
                    dbDest.parentFile?.mkdirs()

                    val incomingDb = File(unzipDir, "health.db")
                    validateSqliteHeader(incomingDb)

                    // Close Room before replacing the file
                    healthDatabase.close()

                    // Atomic swap: write to .new first, then rename into place.
                    // If the process is killed between steps we fall back to .bak.
                    val dbNew = File(dbDest.path + ".new")
                    val dbBak = File(dbDest.path + ".bak")

                    incomingDb.copyTo(dbNew, overwrite = true)
                    val walSrc = File(unzipDir, "health.db-wal")
                    val shmSrc = File(unzipDir, "health.db-shm")

                    if (walSrc.exists()) walSrc.copyTo(File(dbNew.path + "-wal"), overwrite = true)
                    if (shmSrc.exists()) shmSrc.copyTo(File(dbNew.path + "-shm"), overwrite = true)

                    if (dbDest.exists()) dbDest.renameTo(dbBak)
                    dbNew.renameTo(dbDest)

                    // Also rename wal/shm if they exist
                    val walNew = File(dbNew.path + "-wal")
                    val shmNew = File(dbNew.path + "-shm")
                    if (walNew.exists()) walNew.renameTo(File(dbDest.path + "-wal"))
                    if (shmNew.exists()) shmNew.renameTo(File(dbDest.path + "-shm"))

                    // Re-encrypt the restored database with the local Keystore key
                    // This will also handle merging and deleting the plaintext WAL/SHM files
                    sqlCipherKeyManager.migrateIfNeeded(dbDest)

                    // Only clean up the backup once the new DB is in place
                    dbBak.delete()

                    // Restore preferences from JSON
                    val json = JSONObject(File(unzipDir, "preferences.json").readText())
                    restorePreferences(json)

                    unzipDir.deleteRecursively()

                    RestoreResult.SuccessRequiresRestart
                }.getOrElse { RestoreResult.Failure(it) }
            }

        /** Throws [IllegalStateException] if the file does not start with the SQLite magic header. */
        private fun validateSqliteHeader(file: File) {
            val magic = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)
            val header = ByteArray(magic.size)
            file.inputStream().use { it.read(header) }
            check(header.contentEquals(magic)) { "Restore file is not a valid SQLite database" }
        }

        private suspend fun restorePreferences(json: JSONObject) {
            if (json.has(
                    "goalSleepHours",
                )
            ) {
                settingsRepo.updateGoalSleepHours(json.getDouble("goalSleepHours").toFloat())
            }
            if (!json.isNull(
                    "hrvBaselineOverride",
                )
            ) {
                settingsRepo.updateHrvBaselineOverride(json.getDouble("hrvBaselineOverride").toFloat())
            }
            if (!json.isNull(
                    "rhrBaselineOverride",
                )
            ) {
                settingsRepo.updateRhrBaselineOverride(json.getDouble("rhrBaselineOverride").toFloat())
            }
            if (json.has(
                    "syncPreference",
                )
            ) {
                settingsRepo.updateSyncPreference(SyncPreference.valueOf(json.getString("syncPreference")))
            }
            if (json.has("syncIntervalHours")) settingsRepo.updateSyncIntervalHours(json.getInt("syncIntervalHours"))
            if (json.has("maxHeartRate")) settingsRepo.updateMaxHeartRate(json.getInt("maxHeartRate"))
            if (json.has(
                    "hrvOptimalThreshold",
                )
            ) {
                settingsRepo.updateHrvOptimalThreshold(json.getDouble("hrvOptimalThreshold").toFloat())
            }
            if (json.has(
                    "hrvWarningThreshold",
                )
            ) {
                settingsRepo.updateHrvWarningThreshold(json.getDouble("hrvWarningThreshold").toFloat())
            }
            if (json.has(
                    "rhrOptimalThreshold",
                )
            ) {
                settingsRepo.updateRhrOptimalThreshold(json.getDouble("rhrOptimalThreshold").toFloat())
            }
            if (json.has(
                    "rhrWarningThreshold",
                )
            ) {
                settingsRepo.updateRhrWarningThreshold(json.getDouble("rhrWarningThreshold").toFloat())
            }
            if (json.has(
                    "restingHrBeforeMinutes",
                )
            ) {
                settingsRepo.updateRestingHrBeforeMinutes(json.getInt("restingHrBeforeMinutes"))
            }
            if (json.has(
                    "restingHrAfterMinutes",
                )
            ) {
                settingsRepo.updateRestingHrAfterMinutes(json.getInt("restingHrAfterMinutes"))
            }
            if (json.has("appTheme")) settingsRepo.updateAppTheme(AppTheme.valueOf(json.getString("appTheme")))
            if (json.has(
                    "backupSchedule",
                )
            ) {
                settingsRepo.updateBackupSchedule(BackupSchedule.valueOf(json.getString("backupSchedule")))
            }
            if (json.has("birthDay") && json.has("birthMonth") && json.has("birthYear")) {
                settingsRepo.updateBirthday(
                    json.getInt("birthDay"),
                    json.getInt("birthMonth"),
                    json.getInt("birthYear"),
                )
            }
        }

        private fun unzip(
            zipFile: File,
            destDir: File,
        ) {
            val canonicalDest = destDir.canonicalPath
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(canonicalDest + File.separator)) {
                        throw SecurityException("Malicious ZIP entry: ${entry.name}")
                    }
                    outFile.outputStream().use { zis.copyTo(it) }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
