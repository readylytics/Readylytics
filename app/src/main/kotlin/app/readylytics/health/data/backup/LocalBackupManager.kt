package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupLocation
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                            val tempZip = File(tempDir, info.name)
                            val newZipPath = File(tempDir, "reencrypt_new_${System.currentTimeMillis()}.zip")

                            // 1. Copy source to temp zip
                            store.read(info.location).use { input ->
                                tempZip.outputStream().use { output -> input.copyTo(output) }
                            }

                            // 2. Stream entries directly to new zip with new password (no plaintext JSON on disk)
                            ZipFile(tempZip, oldPassword?.toCharArray()).use { source ->
                                net.lingala.zip4j.io.outputstream
                                    .ZipOutputStream(
                                        newZipPath.outputStream(),
                                        newPassword?.toCharArray(),
                                    ).use { sink ->
                                        source.fileHeaders.forEach { header ->
                                            sink.putNextEntry(
                                                ZipParameters().apply {
                                                    fileNameInZip = header.fileName
                                                    if (newPassword != null) {
                                                        isEncryptFiles = true
                                                        encryptionMethod = EncryptionMethod.AES
                                                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                                                    }
                                                },
                                            )
                                            source.getInputStream(header).use { it.copyTo(sink) }
                                            sink.closeEntry()
                                        }
                                    }
                            }

                            // 3. Publish re-encrypted archive atomically
                            store.publish(newZipPath, info.name)

                            // 4. Cleanup temp zip files
                            tempZip.delete()
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

        private suspend inline fun <reified T : Any> writeTable(
            writer: BufferedWriter,
            name: String,
            crossinline page: suspend () -> List<T>,
            crossinline advance: (T) -> Unit,
        ) {
            writer.write("  \"$name\": [\n")
            var first = true
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = page()
                if (batch.isEmpty()) break
                batch.forEach {
                    if (!first) writer.write(",\n")
                    writer.write("    ${json.encodeToString(it)}")
                    first = false
                }
                advance(batch.last())
            }
            writer.write("\n  ]")
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

            // --- Sleep sessions ---
            var sleepAfterTs = Long.MIN_VALUE
            var sleepAfterId = ""
            writeTable<SleepSessionEntity>(
                writer,
                "sleepSessions",
                page = { sleepSessionDao.pageAfter(0, sleepAfterTs, sleepAfterId, 100) },
                advance = {
                    sleepAfterTs = it.startTime
                    sleepAfterId = it.id
                },
            )
            writer.write(",\n")

            // --- Health source records (non-paged, small table) ---
            writer.write("  \"healthSourceRecords\": [\n")
            currentCoroutineContext().ensureActive()
            val sourceRecords = sourceRecordDao.getAll()
            var first = true
            sourceRecords.forEach {
                if (!first) writer.write(",\n")
                writer.write("    ${json.encodeToString(it)}")
                first = false
            }
            writer.write("\n  ],\n")

            // --- Heart rate records (composite cursor) ---
            var hrAfterTs = Long.MIN_VALUE
            var hrAfterRef = Long.MIN_VALUE
            writeTable<HeartRateRecordEntity>(
                writer,
                "heartRateRecords",
                page = { heartRateDao.pageAfter(0, hrAfterTs, hrAfterRef, 500) },
                advance = {
                    hrAfterTs = it.timestampMs
                    hrAfterRef = it.sourceRecordRef
                },
            )
            writer.write(",\n")

            // --- HRV records ---
            var hrvAfterTs = Long.MIN_VALUE
            var hrvAfterRef = Long.MIN_VALUE
            writeTable<HrvRecordEntity>(
                writer,
                "hrvRecords",
                page = { hrvDao.pageAfter(0, hrvAfterTs, hrvAfterRef, 500) },
                advance = {
                    hrvAfterTs = it.timestampMs
                    hrvAfterRef = it.sourceRecordRef
                },
            )
            writer.write(",\n")

            // --- HR minute buckets ---
            var mbAfterTs = Long.MIN_VALUE
            var mbAfterRecordType = ""
            var mbAfterSessionId = ""
            writeTable<HrMinuteBucketEntity>(
                writer,
                "hrMinuteBuckets",
                page = { minuteBucketDao.pageAfter(mbAfterTs, mbAfterRecordType, mbAfterSessionId, 500) },
                advance = {
                    mbAfterTs = it.bucketStartMs
                    mbAfterRecordType = it.recordType
                    mbAfterSessionId =
                        it.sessionId
                },
            )
            writer.write(",\n")

            // --- Workouts ---
            var workoutAfterTs = Long.MIN_VALUE
            var workoutAfterId = ""
            writeTable<WorkoutRecordEntity>(
                writer,
                "workouts",
                page = { workoutDao.pageAfter(0, workoutAfterTs, workoutAfterId, 100) },
                advance = {
                    workoutAfterTs = it.startTime
                    workoutAfterId = it.id
                },
            )
            writer.write(",\n")

            // Written after "workouts" so the streaming restore inserts the parent rows first --
            // workout_route_points has an ON DELETE CASCADE foreign key onto workout_records.
            var routeAfterId = Long.MIN_VALUE
            writeTable<WorkoutRoutePointEntity>(
                writer,
                "workoutRoutePoints",
                page = { workoutRoutePointDao.pageAfter(routeAfterId, 500) },
                advance = { routeAfterId = it.id },
            )
            writer.write(",\n")

            // --- Daily summaries ---
            var summaryAfterTs = Long.MIN_VALUE
            writeTable<DailySummaryEntity>(
                writer,
                "dailySummaries",
                page = { dailySummaryDao.pageAfter(0, summaryAfterTs, 100) },
                advance = { summaryAfterTs = it.dateMidnightMs },
            )
            writer.write(",\n")

            // --- Weight records ---
            var weightAfterTs = Long.MIN_VALUE
            var weightAfterId = ""
            writeTable<WeightRecordEntity>(
                writer,
                "weightRecords",
                page = { weightRecordDao.pageAfter(0, weightAfterTs, weightAfterId, 100) },
                advance = {
                    weightAfterTs = it.timestampMs
                    weightAfterId = it.id
                },
            )
            writer.write(",\n")

            // --- Body fat records ---
            var bodyFatAfterTs = Long.MIN_VALUE
            var bodyFatAfterId = ""
            writeTable<BodyFatRecordEntity>(
                writer,
                "bodyFatRecords",
                page = { bodyFatRecordDao.pageAfter(0, bodyFatAfterTs, bodyFatAfterId, 100) },
                advance = {
                    bodyFatAfterTs = it.timestampMs
                    bodyFatAfterId = it.id
                },
            )
            writer.write(",\n")

            // --- Blood pressure records ---
            var bpAfterTs = Long.MIN_VALUE
            var bpAfterId = ""
            writeTable<BloodPressureRecordEntity>(
                writer,
                "bloodPressureRecords",
                page = { bloodPressureRecordDao.pageAfter(0, bpAfterTs, bpAfterId, 100) },
                advance = {
                    bpAfterTs = it.timestampMs
                    bpAfterId = it.id
                },
            )
            writer.write(",\n")

            // --- Oxygen saturation records ---
            var o2AfterTs = Long.MIN_VALUE
            var o2AfterId = ""
            writeTable<OxygenSaturationRecordEntity>(
                writer,
                "oxygenSaturationRecords",
                page = { oxygenSaturationRecordDao.pageAfter(0, o2AfterTs, o2AfterId, 100) },
                advance = {
                    o2AfterTs = it.timestampMs
                    o2AfterId = it.id
                },
            )
            writer.write(",\n")

            // --- Body temperature records ---
            var tempAfterTs = Long.MIN_VALUE
            var tempAfterId = ""
            writeTable<BodyTemperatureRecordEntity>(
                writer,
                "bodyTemperatureRecords",
                page = { bodyTemperatureRecordDao.pageAfter(0, tempAfterTs, tempAfterId, 100) },
                advance = {
                    tempAfterTs = it.timestampMs
                    tempAfterId = it.id
                },
            )
            writer.write(",\n")

            // --- Step records ---
            var stepAfterTs = Long.MIN_VALUE
            var stepAfterId = ""
            writeTable<StepRecordEntity>(
                writer,
                "stepRecords",
                page = { stepRecordDao.pageAfter(0, stepAfterTs, stepAfterId, 500) },
                advance = {
                    stepAfterTs = it.startTime
                    stepAfterId = it.id
                },
            )

            writer.write("\n}\n")
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
                    sleepScoreWeightProfile = prefs.sleepScoreWeightProfile.name,
                    hypersomniaOnsetPercent = prefs.hypersomniaOnsetPercent,
                    scoringVersion = prefs.scoringVersion,
                    lastRecalcSleepScoreWeightProfile = prefs.lastRecalcSleepScoreWeightProfile?.name,
                    lastRecalcGoalSleepHours = prefs.lastRecalcGoalSleepHours,
                    lastRecalcHypersomniaOnsetPercent = prefs.lastRecalcHypersomniaOnsetPercent,
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
