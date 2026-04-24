package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthState
import com.gregor.lauritz.healthdashboard.data.drive.GoogleDriveRepository
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
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
                    val dbFile = context.getDatabasePath("health_dashboard.db")
                    // Copy the main DB file and WAL companion files so the backup is consistent
                    dbFile.copyTo(File(tempDir, "health.db"), overwrite = true)
                    val walFile = File("${dbFile.path}-wal")
                    val shmFile = File("${dbFile.path}-shm")
                    if (walFile.exists()) walFile.copyTo(File(tempDir, "health.db-wal"), overwrite = true)
                    if (shmFile.exists()) shmFile.copyTo(File(tempDir, "health.db-shm"), overwrite = true)

                    val prefsJson = exportPreferencesToJson()
                    File(tempDir, "preferences.json").writeText(prefsJson)

                    val zipFile = File(context.cacheDir, "health_backup.zip")
                    zipDirectory(tempDir, zipFile)

                    // Delete existing backup (keep only the latest)
                    val existing = driveRepository.listBackupFiles(accessToken)
                    existing.forEach { driveRepository.deleteFile(accessToken, it.id) }

                    driveRepository.uploadBackup(accessToken, zipFile)
                    prefsRepo.updateLastBackupTimestamp(System.currentTimeMillis())

                    tempDir.deleteRecursively()
                    zipFile.delete()
                    Unit
                }
            }

        private suspend fun exportPreferencesToJson(): String {
            val prefs = prefsRepo.userPreferences.first()
            return JSONObject()
                .put("goalSleepHours", prefs.goalSleepHours)
                .put("hrvBaselineOverride", prefs.hrvBaselineOverride ?: JSONObject.NULL)
                .put("rhrBaselineOverride", prefs.rhrBaselineOverride ?: JSONObject.NULL)
                .put("syncPreference", prefs.syncPreference.name)
                .put("syncIntervalHours", prefs.syncIntervalHours)
                .put("maxHeartRate", prefs.maxHeartRate)
                .put("hrvOptimalThreshold", prefs.hrvOptimalThreshold)
                .put("hrvWarningThreshold", prefs.hrvWarningThreshold)
                .put("rhrOptimalThreshold", prefs.rhrOptimalThreshold)
                .put("rhrWarningThreshold", prefs.rhrWarningThreshold)
                .put("restingHrBeforeMinutes", prefs.restingHrBeforeMinutes)
                .put("restingHrAfterMinutes", prefs.restingHrAfterMinutes)
                .put("appTheme", prefs.appTheme.name)
                .put("backupSchedule", prefs.backupSchedule.name)
                .toString()
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
