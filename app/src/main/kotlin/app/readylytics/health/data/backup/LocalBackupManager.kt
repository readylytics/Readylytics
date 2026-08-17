package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupLocation
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        @param:ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val cardConfigurationRepository: CardConfigurationRepository,
        private val vitalsLayoutRepository: VitalsLayoutRepository,
        private val sleepLayoutRepository: SleepLayoutRepository,
        private val workoutsLayoutRepository: WorkoutsLayoutRepository,
        private val workoutDetailLayoutRepository: WorkoutDetailLayoutRepository,
        private val encryptionManager: EncryptionManager,
        private val auditTrailRepository: AuditTrailRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val backupStoreFactory: BackupStoreFactory = DefaultBackupStoreFactory(context),
    ) {
        private val defaultBackupDir = File(context.filesDir, "backups")
        private val json = Json { encodeDefaults = true }

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
                        writeJsonStreaming(fos)
                    }

                    // 2. Fetch and decrypt backup password
                    val password =
                        prefs.backupPasswordHash?.let { hash ->
                            encryptionManager.decrypt(hash)
                        } ?: throw IllegalStateException("Backup password not set")

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
                            val tempZip = File(tempDir, info.name)
                            val tempJson = File(tempDir, info.name.replace(".zip", ".json"))
                            val newZipPath = File(tempDir, "reencrypt_new_${System.currentTimeMillis()}.zip")

                            // 1. Copy to temp zip
                            store.read(info.location).use { input ->
                                tempZip.outputStream().use { output -> input.copyTo(output) }
                            }

                            // 2. Extract
                            val zipFile = ZipFile(tempZip, oldPassword?.toCharArray())
                            zipFile.extractAll(tempDir.absolutePath)

                            // 3. Re-zip with new password to separate temp path (atomic write)
                            val tempZipForNew = File(tempDir, "temp_new_plain.zip")
                            val newZip = ZipFile(tempZipForNew, newPassword?.toCharArray())
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

                            if (!tempZipForNew.renameTo(newZipPath)) {
                                tempZipForNew.copyTo(newZipPath, overwrite = true)
                                tempZipForNew.delete()
                            }

                            // 4. Overwrite original
                            store.publish(newZipPath, info.name)

                            // 5. Cleanup per-backup temp files
                            tempZip.delete()
                            tempJson.delete()
                            newZipPath.delete()
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

        private fun moveTempZipToFinal(
            tempZipFile: File,
            finalFile: File,
        ) {
            finalFile.delete()
            if (!tempZipFile.renameTo(finalFile)) {
                tempZipFile.copyTo(finalFile, overwrite = true)
                tempZipFile.delete()
            }
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
            val workoutRoutePointDao = healthDatabase.workoutRoutePointDao()
            val dailySummaryDao = healthDatabase.dailySummaryDao()
            val weightRecordDao = healthDatabase.weightRecordDao()
            val bodyFatRecordDao = healthDatabase.bodyFatRecordDao()
            val bloodPressureRecordDao = healthDatabase.bloodPressureRecordDao()
            val oxygenSaturationRecordDao = healthDatabase.oxygenSaturationRecordDao()
            val bodyTemperatureRecordDao = healthDatabase.bodyTemperatureRecordDao()
            val stepRecordDao = healthDatabase.stepRecordDao()
            val sourceRecordDao = healthDatabase.sourceRecordDao()
            val minuteBucketDao = healthDatabase.minuteBucketDao()

            val writer = outputStream.bufferedWriter()
            writer.write("{\n")
            writer.write("  \"schemaVersion\": ${HealthDatabase.DATABASE_VERSION},\n")
            writer.write("  \"exportedAt\": \"${Instant.now()}\",\n")

            val rowCounts =
                coroutineScope {
                    val counts =
                        listOf(
                            "sleepSessions" to async { sleepSessionDao.count() },
                            "heartRateRecords" to async { heartRateDao.count() },
                            "hrvRecords" to async { hrvDao.count() },
                            "workouts" to async { workoutDao.count() },
                            "workoutRoutePoints" to async { workoutRoutePointDao.count() },
                            "dailySummaries" to async { dailySummaryDao.count() },
                            "weightRecords" to async { weightRecordDao.count() },
                            "bodyFatRecords" to async { bodyFatRecordDao.count() },
                            "bloodPressureRecords" to async { bloodPressureRecordDao.count() },
                            "oxygenSaturationRecords" to async { oxygenSaturationRecordDao.count() },
                            "bodyTemperatureRecords" to async { bodyTemperatureRecordDao.count() },
                            "stepRecords" to async { stepRecordDao.count() },
                            "healthSourceRecords" to async { sourceRecordDao.count() },
                            "hrMinuteBuckets" to async { minuteBucketDao.count() },
                        )
                    counts.associate { (key, deferred) -> key to deferred.await() }
                }

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

            writer.write("  \"healthSourceRecords\": [\n")
            val sourceRecords = sourceRecordDao.getAll()
            first = true
            sourceRecords.forEach {
                if (!first) writer.write(",\n")
                writer.write("    ${json.encodeToString(it)}")
                first = false
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

            writer.write("  \"hrMinuteBuckets\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = minuteBucketDao.getPaged(500, offset)
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

            // Written after "workouts" so the streaming restore inserts the parent rows first --
            // workout_route_points has an ON DELETE CASCADE foreign key onto workout_records.
            writer.write("  \"workoutRoutePoints\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = workoutRoutePointDao.getPaged(500, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 500
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
            writer.write("\n  ],\n")

            writer.write("  \"weightRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = weightRecordDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ],\n")

            writer.write("  \"bodyFatRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = bodyFatRecordDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ],\n")

            writer.write("  \"bloodPressureRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = bloodPressureRecordDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ],\n")

            writer.write("  \"oxygenSaturationRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = oxygenSaturationRecordDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ],\n")

            writer.write("  \"bodyTemperatureRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = bodyTemperatureRecordDao.getPaged(0, 100, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 100
            }
            writer.write("\n  ],\n")

            writer.write("  \"stepRecords\": [\n")
            offset = 0
            first = true
            while (true) {
                val batch = stepRecordDao.getPaged(0, 500, offset)
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                offset += 500
            }
            writer.write("\n  ]\n")

            writer.write("}\n")
            writer.flush()
        }

        private suspend fun writePreferences(writer: BufferedWriter) {
            val prefs = settingsRepository.userPreferences.first()
            val cards = cardConfigurationRepository.dashboardCardConfigurations().first()
            val vitalsCards = vitalsLayoutRepository.vitalsCardConfigurations().first()
            val vitalsCharts = vitalsLayoutRepository.vitalsChartConfigurations().first()
            val sleepTopCards = sleepLayoutRepository.sleepTopCardConfigurations().first()
            val sleepCharts = sleepLayoutRepository.sleepChartConfigurations().first()
            val sleepMetricCards = sleepLayoutRepository.sleepMetricCardConfigurations().first()
            val workoutCards = workoutsLayoutRepository.workoutCardConfigurations().first()
            val workoutCharts = workoutsLayoutRepository.workoutChartConfigurations().first()
            val workoutHistory = workoutsLayoutRepository.workoutHistoryConfigurations().first()
            val workoutDetailLayouts =
                workoutDetailLayoutRepository.allLayouts().first().mapKeys { it.key.name }
            val backup =
                UserPreferencesBackup(
                    goalSleepHours = prefs.goalSleepHours,
                    hrvBaselineOverride = prefs.hrvBaselineOverride,
                    rhrBaselineOverride = prefs.rhrBaselineOverride,
                    syncPreference = prefs.syncPreference.name,
                    syncIntervalHours = prefs.syncIntervalHours,
                    backgroundSyncEnabled = prefs.backgroundSyncEnabled,
                    backgroundSyncIntervalMinutes = prefs.backgroundSyncIntervalMinutes,
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
                    birthDate = prefs.birthDate,
                    // Extract day, month, year from birthDate for backward compatibility
                    birthDay =
                        prefs.birthDate?.let {
                            try {
                                java.time.LocalDate
                                    .parse(it)
                                    .dayOfMonth
                            } catch (
                                e: Exception,
                            ) {
                                null
                            }
                        },
                    birthMonth =
                        prefs.birthDate?.let {
                            try {
                                java.time.LocalDate
                                    .parse(it)
                                    .monthValue
                            } catch (
                                e: Exception,
                            ) {
                                null
                            }
                        },
                    birthYear =
                        prefs.birthDate?.let {
                            try {
                                java.time.LocalDate
                                    .parse(it)
                                    .year
                            } catch (
                                e: Exception,
                            ) {
                                null
                            }
                        },
                    gender = prefs.gender?.name,
                    heightCm = prefs.heightCm,
                    hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                    hrvWarningThreshold = prefs.hrvWarningThreshold,
                    rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                    rhrWarningThreshold = prefs.rhrWarningThreshold,
                    hrrToleranceSeconds = prefs.hrrToleranceSeconds,
                    appTheme = prefs.appTheme.name,
                    backupSchedule = prefs.backupSchedule.name,
                    lastBackupTimestamp = prefs.lastBackupTimestamp,
                    consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
                    consistencyEvaluationDays = prefs.consistencyEvaluationDays,
                    consistencyBaselineDays = prefs.consistencyBaselineDays,
                    rasScalingFactor = prefs.rasScalingFactor,
                    stepGoal = prefs.stepGoal,
                    retentionDaysEnabled = prefs.retentionDaysEnabled,
                    retentionDays = prefs.retentionDays,
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
                    deviceByDataType = prefs.deviceByDataType.takeIf { it.isNotEmpty() },
                    backupDirectoryUri = prefs.backupDirectoryUri,
                    dashboardCards = cards,
                    vitalsCards = vitalsCards,
                    vitalsCharts = vitalsCharts,
                    sleepTopCards = sleepTopCards,
                    sleepCharts = sleepCharts,
                    sleepMetricCards = sleepMetricCards,
                    workoutCards = workoutCards,
                    workoutCharts = workoutCharts,
                    workoutHistory = workoutHistory,
                    workoutDetailLayouts = workoutDetailLayouts,
                )
            writer.write(json.encodeToString(backup))
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
