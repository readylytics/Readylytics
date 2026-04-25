package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import android.content.Intent
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthState
import com.gregor.lauritz.healthdashboard.data.drive.GoogleDriveRepository
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
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
        private val prefsRepo: UserPreferencesRepository,
        private val appConfigRepo: AppConfigRepository,
        private val backupPrefsRepo: BackupPreferencesRepository,
    ) {
        sealed class RestoreResult {
            data object Success : RestoreResult()
            data object SuccessRequiresRestart : RestoreResult()
            data class Failure(val cause: Throwable) : RestoreResult()
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

                    val zipDest = File(context.cacheDir, "restore_temp.zip")
                    driveRepository.downloadBackup(accessToken, backupFile.id, zipDest)

                    val unzipDir = File(context.cacheDir, "restore_unzipped").also { it.deleteRecursively(); it.mkdirs() }
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

                    // Close Room before replacing the file
                    healthDatabase.close()

                    // Replace DB files atomically
                    File(unzipDir, "health.db").copyTo(dbDest, overwrite = true)
                    val walSrc = File(unzipDir, "health.db-wal")
                    val shmSrc = File(unzipDir, "health.db-shm")
                    if (walSrc.exists()) walSrc.copyTo(File("${dbDest.path}-wal"), overwrite = true)
                    if (shmSrc.exists()) shmSrc.copyTo(File("${dbDest.path}-shm"), overwrite = true)

                    // Restore preferences from JSON
                    val json = JSONObject(File(unzipDir, "preferences.json").readText())
                    restorePreferences(json)

                    unzipDir.deleteRecursively()

                    RestoreResult.SuccessRequiresRestart
                }.getOrElse { RestoreResult.Failure(it) }
            }

        private suspend fun restorePreferences(json: JSONObject) {
            if (json.has("goalSleepHours")) prefsRepo.updateGoalSleepHours(json.getDouble("goalSleepHours").toFloat())
            if (!json.isNull("hrvBaselineOverride")) prefsRepo.updateHrvBaselineOverride(json.getDouble("hrvBaselineOverride").toFloat())
            if (!json.isNull("rhrBaselineOverride")) prefsRepo.updateRhrBaselineOverride(json.getDouble("rhrBaselineOverride").toFloat())
            if (json.has("syncPreference")) appConfigRepo.updateSyncPreference(SyncPreference.valueOf(json.getString("syncPreference")))
            if (json.has("syncIntervalHours")) appConfigRepo.updateSyncIntervalHours(json.getInt("syncIntervalHours"))
            if (json.has("maxHeartRate")) prefsRepo.updateMaxHeartRate(json.getInt("maxHeartRate"))
            if (json.has("hrvOptimalThreshold")) prefsRepo.updateHrvOptimalThreshold(json.getDouble("hrvOptimalThreshold").toFloat())
            if (json.has("hrvWarningThreshold")) prefsRepo.updateHrvWarningThreshold(json.getDouble("hrvWarningThreshold").toFloat())
            if (json.has("rhrOptimalThreshold")) prefsRepo.updateRhrOptimalThreshold(json.getDouble("rhrOptimalThreshold").toFloat())
            if (json.has("rhrWarningThreshold")) prefsRepo.updateRhrWarningThreshold(json.getDouble("rhrWarningThreshold").toFloat())
            if (json.has("restingHrBeforeMinutes")) prefsRepo.updateRestingHrBeforeMinutes(json.getInt("restingHrBeforeMinutes"))
            if (json.has("restingHrAfterMinutes")) prefsRepo.updateRestingHrAfterMinutes(json.getInt("restingHrAfterMinutes"))
            if (json.has("appTheme")) appConfigRepo.updateAppTheme(AppTheme.valueOf(json.getString("appTheme")))
            if (json.has("backupSchedule")) backupPrefsRepo.updateBackupSchedule(BackupSchedule.valueOf(json.getString("backupSchedule")))
            if (json.has("birthDay") && json.has("birthMonth") && json.has("birthYear")) {
                prefsRepo.updateBirthday(
                    json.getInt("birthDay"),
                    json.getInt("birthMonth"),
                    json.getInt("birthYear"),
                )
            }
        }

        private fun unzip(
            zipFile: File,
            destDir: File,
        ): Unit {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    outFile.outputStream().use { zis.copyTo(it) }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
