package com.gregor.lauritz.healthdashboard.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.domain.backup.BackupFileInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val encryptionManager: EncryptionManager,
    ) {
        private val defaultBackupDir = File(context.filesDir, "backups")
        private val json = Json { encodeDefaults = true }

        suspend fun createBackup(): Result<File?> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val prefs = settingsRepository.userPreferences.first()
                    val customUri = prefs.backupDirectoryUri?.toUri()

                    // Prune old backups from both internal and custom locations
                    pruneOldBackups(customUri)

                    val timestamp =
                        Instant.now().atZone(ZoneId.systemDefault()).format(FILENAME_FORMATTER)
                    val jsonFilename = "backup_$timestamp.json"
                    val zipFilename = "backup_$timestamp.zip"

                    // 1. Write JSON to a temporary file
                    val tempJsonFile = File(context.cacheDir, jsonFilename)
                    FileOutputStream(tempJsonFile).use { fos ->
                        writeJsonStreaming(fos)
                    }

                    // 2. Fetch and decrypt backup password
                    val password =
                        prefs.backupPasswordHash?.let { hash ->
                            encryptionManager.decrypt(hash)
                        }

                    // 3. Create ZIP file
                    val finalFile: File?

                    if (customUri != null) {
                        val dir =
                            DocumentFile.fromTreeUri(context, customUri)
                                ?: throw IllegalStateException("Could not access custom backup directory")
                        val file =
                            dir.createFile("application/zip", zipFilename)
                                ?: throw IllegalStateException("Could not create backup file in custom directory")

                        val tempZipFile = File(context.cacheDir, zipFilename)
                        createZip(tempJsonFile, tempZipFile, password)

                        context.contentResolver.openOutputStream(file.uri)?.use { os ->
                            tempZipFile.inputStream().use { it.copyTo(os) }
                        }
                        tempZipFile.delete()
                        finalFile = null
                    } else {
                        defaultBackupDir.mkdirs()
                        val file = File(defaultBackupDir, zipFilename)
                        createZip(tempJsonFile, file, password)
                        finalFile = file
                    }

                    // 4. Cleanup
                    tempJsonFile.delete()

                    finalFile
                }
            }

        suspend fun deleteBackup(uri: Uri): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    if (uri.scheme == "content") {
                        val documentFile = DocumentFile.fromSingleUri(context, uri)
                        if (documentFile?.delete() == false) {
                            val prefs = settingsRepository.userPreferences.first()
                            val customUri = prefs.backupDirectoryUri?.toUri()
                            if (customUri != null) {
                                val root = DocumentFile.fromTreeUri(context, customUri)
                                val file = root?.findFile(documentFile.name ?: "")
                                if (file?.delete() == false) {
                                    throw IllegalStateException("Failed to delete SAF document")
                                }
                            } else {
                                throw IllegalStateException("Failed to delete document")
                            }
                        }
                    } else if (uri.scheme == "file") {
                        val file = File(uri.path!!)
                        if (file.exists() && !file.delete()) {
                            throw IllegalStateException("Failed to delete local file")
                        }
                    }
                }.map { }
            }

        suspend fun reencryptBackups(
            oldPassword: String?,
            newPassword: String?,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val backups = listBackups()
                    val tempDir = File(context.cacheDir, "reencrypt_temp")
                    tempDir.mkdirs()

                    try {
                        backups.forEach { info ->
                            val tempZip = File(tempDir, info.name)
                            val tempJson = File(tempDir, info.name.replace(".zip", ".json"))
                            val newZipPath = File(tempDir, "reencrypt_new_${System.currentTimeMillis()}.zip")

                            // 1. Copy to temp zip
                            if (info.uri.scheme == "content") {
                                context.contentResolver.openInputStream(info.uri)?.use { input ->
                                    tempZip.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            } else {
                                File(info.uri.path!!).copyTo(tempZip, overwrite = true)
                            }

                            // 2. Extract
                            val zipFile = ZipFile(tempZip, oldPassword?.toCharArray())
                            zipFile.extractAll(tempDir.absolutePath)

                            // 3. Re-zip with new password to separate temp path (atomic write)
                            val newZip = ZipFile(newZipPath, newPassword?.toCharArray())
                            val parameters =
                                ZipParameters().apply {
                                    if (newPassword != null) {
                                        isEncryptFiles = true
                                        encryptionMethod = EncryptionMethod.AES
                                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                                    }
                                }
                            newZip.addFile(tempJson, parameters)
                            newZip.close()

                            // 4. Overwrite original (atomic rename-swap)
                            if (info.uri.scheme == "content") {
                                context.contentResolver.openOutputStream(info.uri, "wt")?.use { output ->
                                    newZipPath.inputStream().use { it.copyTo(output) }
                                }
                            } else {
                                newZipPath.renameTo(File(info.uri.path!!))
                            }

                            // 5. Cleanup per-backup temp files
                            tempZip.delete()
                            tempJson.delete()
                            newZipPath.delete()
                        }
                    } finally {
                        tempDir.deleteRecursively()
                    }
                }.map { }
            }

        private fun createZip(
            inputFile: File,
            zipFile: File,
            password: String?,
        ) {
            val zip = ZipFile(zipFile, password?.toCharArray())
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

        private suspend fun writeJsonStreaming(outputStream: OutputStream) {
            val sleepSessionDao = healthDatabase.sleepSessionDao()
            val heartRateDao = healthDatabase.heartRateDao()
            val hrvDao = healthDatabase.hrvDao()
            val workoutDao = healthDatabase.workoutDao()
            val dailySummaryDao = healthDatabase.dailySummaryDao()

            val writer = outputStream.bufferedWriter()
            writer.write("{\n")
            writer.write("  \"schemaVersion\": ${HealthDatabase.DATABASE_VERSION},\n")
            writer.write("  \"exportedAt\": \"${Instant.now()}\",\n")

            val rowCounts =
                mapOf(
                    "sleepSessions" to sleepSessionDao.count(),
                    "heartRateRecords" to heartRateDao.count(),
                    "hrvRecords" to hrvDao.count(),
                    "workouts" to workoutDao.count(),
                    "dailySummaries" to dailySummaryDao.count(),
                )

            writer.write("  \"rowCounts\": ${json.encodeToString(rowCounts)},\n")

            writer.write("  \"preferences\": ")
            writePreferences(writer)
            writer.write(",\n")

            writer.write("  \"sleepSessions\": [\n")
            var offset = 0
            var first = true
            while (true) {
                val batch = sleepSessionDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ],\n")

            writer.write("  \"heartRateRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = heartRateDao.getPaged(0, 500, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 500
            }
            writer.write("\n  ],\n")

            writer.write("  \"hrvRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = hrvDao.getPaged(0, 500, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 500
            }
            writer.write("\n  ],\n")

            writer.write("  \"workouts\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = workoutDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ],\n")

            writer.write("  \"dailySummaries\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = dailySummaryDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ]\n")

            writer.write("}\n")
            writer.flush()
        }

        private suspend fun writePreferences(writer: BufferedWriter) {
            val prefs = settingsRepository.userPreferences.first()
            val backup =
                UserPreferencesBackup(
                    goalSleepHours = prefs.goalSleepHours,
                    hrvBaselineOverride = prefs.hrvBaselineOverride,
                    rhrBaselineOverride = prefs.rhrBaselineOverride,
                    syncPreference = prefs.syncPreference.name,
                    syncIntervalHours = prefs.syncIntervalHours,
                    lastSyncTimestamp = prefs.lastSyncTimestamp,
                    maxHeartRate = prefs.maxHeartRate,
                    autoCalculateMaxHr = prefs.autoCalculateMaxHr,
                    manualZoneEditing = prefs.manualZoneEditing,
                    zone1MinPercent = prefs.zone1MinPercent,
                    zone1MaxPercent = prefs.zone1MaxPercent,
                    zone2MaxPercent = prefs.zone2MaxPercent,
                    zone3MaxPercent = prefs.zone3MaxPercent,
                    zone4MaxPercent = prefs.zone4MaxPercent,
                    zone1MinBpm = prefs.zone1MinBpm,
                    zone1MaxBpm = prefs.zone1MaxBpm,
                    zone2MaxBpm = prefs.zone2MaxBpm,
                    zone3MaxBpm = prefs.zone3MaxBpm,
                    zone4MaxBpm = prefs.zone4MaxBpm,
                    age = prefs.age,
                    birthDay = prefs.birthDay,
                    birthMonth = prefs.birthMonth,
                    birthYear = prefs.birthYear,
                    gender = prefs.gender?.name,
                    hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                    hrvWarningThreshold = prefs.hrvWarningThreshold,
                    rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                    rhrWarningThreshold = prefs.rhrWarningThreshold,
                    restingHrBeforeMinutes = prefs.restingHrBeforeMinutes,
                    restingHrAfterMinutes = prefs.restingHrAfterMinutes,
                    appTheme = prefs.appTheme.name,
                    driveAccountEmail = prefs.driveAccountEmail,
                    backupSchedule = prefs.backupSchedule.name,
                    lastBackupTimestamp = prefs.lastBackupTimestamp,
                    consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
                    consistencyEvaluationDays = prefs.consistencyEvaluationDays,
                    consistencyBaselineDays = prefs.consistencyBaselineDays,
                    paiScalingFactor = prefs.paiScalingFactor,
                    stepGoal = prefs.stepGoal,
                    retentionDaysEnabled = prefs.retentionDaysEnabled,
                    retentionDays = prefs.retentionDays,
                    collapseCloudData = prefs.collapseCloudData,
                    collapseHealthConnect = prefs.collapseHealthConnect,
                    collapseBaselinesThresholds = prefs.collapseBaselinesThresholds,
                    collapseDisplay = prefs.collapseDisplay,
                    collapseAdvanced = prefs.collapseAdvanced,
                    aboutDismissed = prefs.aboutDismissed,
                    physiologyProfile = prefs.physiologyProfile.name,
                    installDate = prefs.installDate,
                    circadianThresholdOverride = prefs.circadianThresholdOverride,
                    dynamicColorEnabled = prefs.dynamicColorEnabled,
                    trimpModel = prefs.trimpModel.name,
                    banisterMultiplier = prefs.banisterMultiplier,
                    chengBeta = prefs.chengBeta,
                    itrimB = prefs.itrimB,
                    primaryDeviceName = prefs.primaryDeviceName,
                    backupDirectoryUri = prefs.backupDirectoryUri,
                )
            writer.write(json.encodeToString(backup))
        }

        suspend fun listBackups(): List<BackupFileInfo> =
            withContext(Dispatchers.IO) {
                val prefs = settingsRepository.userPreferences.first()
                val customUri = prefs.backupDirectoryUri?.toUri()

                if (customUri != null) {
                    val dir = DocumentFile.fromTreeUri(context, customUri)
                    dir
                        ?.listFiles()
                        ?.filter { it.name?.startsWith("backup_") == true && it.name?.endsWith(".zip") == true }
                        ?.map { BackupFileInfo(it.name!!, it.lastModified(), it.length(), it.uri) }
                        ?.sortedByDescending { it.lastModified }
                        ?: emptyList()
                } else {
                    defaultBackupDir.mkdirs()
                    defaultBackupDir
                        .listFiles { f -> f.name.startsWith("backup_") && f.name.endsWith(".zip") }
                        ?.map { BackupFileInfo(it.name, it.lastModified(), it.length(), Uri.fromFile(it)) }
                        ?.sortedByDescending { it.lastModified }
                        ?: emptyList()
                }
            }

        private fun pruneOldBackups(customUri: Uri?) {
            val now = System.currentTimeMillis()

            // 1. Prune internal directory
            if (defaultBackupDir.exists()) {
                defaultBackupDir
                    .listFiles { f ->
                        f.name.startsWith("backup_") && f.name.endsWith(".zip") && f.isFile
                    }?.filter { now - it.lastModified() > BACKUP_RETENTION_PERIOD_MS }
                    ?.forEach { it.delete() }
            }

            // 2. Prune custom SAF directory if provided
            if (customUri != null) {
                val treeDir =
                    if (customUri.scheme == "content") {
                        DocumentFile.fromTreeUri(context, customUri)
                    } else {
                        // Support for file:// URIs (e.g. in tests)
                        customUri.path?.let { DocumentFile.fromFile(File(it)) }
                    }
                treeDir
                    ?.listFiles()
                    ?.filter {
                        it.isFile && it.name?.startsWith("backup_") == true && it.name?.endsWith(".zip") == true
                    }?.filter { now - it.lastModified() > BACKUP_RETENTION_PERIOD_MS }
                    ?.forEach { it.delete() }
            }
        }

        companion object {
            private val FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            private const val BACKUP_RETENTION_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
        }
    }
