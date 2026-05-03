package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthState
import com.gregor.lauritz.healthdashboard.data.drive.GoogleDriveRepository
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.preferences.BackupPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
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
        private val prefsRepo: UserPreferencesRepository,
        private val backupPrefsRepo: BackupPreferencesRepository,
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
                    try {
                        val dbFile = context.getDatabasePath("health_dashboard.db")
                        // Copy the main DB file and WAL companion files so the backup is consistent
                        dbFile.copyTo(File(tempDir, "health.db"), overwrite = true)
                        val walFile = File("${dbFile.path}-wal")
                        val shmFile = File("${dbFile.path}-shm")
                        if (walFile.exists()) walFile.copyTo(File(tempDir, "health.db-wal"), overwrite = true)
                        if (shmFile.exists()) shmFile.copyTo(File(tempDir, "health.db-shm"), overwrite = true)

                        val prefsJson = exportPreferencesToJson()
                        File(tempDir, "preferences.json").writeText(prefsJson)

                        zipDirectory(tempDir, zipFile)

                        // Validate the ZIP before upload — guard against corrupt archives
                        require(zipFile.exists() && zipFile.length() > 0) { "Backup ZIP creation failed" }
                        ZipFile(zipFile).use { /* validates ZIP structure */ }

                        // Upload first so an upload failure keeps the previous backup intact
                        val uploadedId = driveRepository.uploadBackup(accessToken, zipFile)
                        backupPrefsRepo.updateLastBackupTimestamp(System.currentTimeMillis())

                        // Prune old backups, keeping the one we just uploaded plus one previous version
                        val existing = driveRepository.listBackupFiles(accessToken)
                            .filter { it.id != uploadedId }
                            .sortedByDescending { it.name }
                        existing.drop(1).forEach { driveRepository.deleteFile(accessToken, it.id) }
                    } finally {
                        tempDir.deleteRecursively()
                        zipFile.delete()
                    }
                    Unit
                }
            }

        private suspend fun exportPreferencesToJson(): String {
            val prefs = prefsRepo.userPreferences.first()
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
