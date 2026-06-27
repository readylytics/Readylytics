package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.preferences.AppThemeProto
import app.readylytics.health.data.preferences.BackupScheduleProto
import app.readylytics.health.data.preferences.PhysiologyProfileProto
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SyncPreferenceProto
import app.readylytics.health.data.preferences.TrimpMethodProto
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.util.logW
import app.readylytics.health.workers.WorkerScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File
import java.io.InputStreamReader
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

class WrongBackupPasswordException : Exception("Incorrect backup password")

@Singleton
class LocalRestoreManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val cardConfigurationRepository: CardConfigurationRepository,
        private val workerScheduler: WorkerScheduler,
        private val encryptionManager: EncryptionManager,
        private val auditTrailRepository: AuditTrailRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        enum class RestoreStage {
            VALIDATION,
            DATABASE,
            PREFERENCES,
            CARD_CONFIGURATION,
            WORK_SCHEDULING,
        }

        sealed class RestoreResult {
            data object Success : RestoreResult()

            data object SuccessRequiresRestart : RestoreResult()

            data class PartialSuccessRequiresRestart(
                val failedStage: RestoreStage,
                val cause: Throwable,
            ) : RestoreResult()

            data class Failure(
                val cause: Throwable,
            ) : RestoreResult()
        }

        suspend fun validate(
            backupUri: Uri,
            providedPassword: String? = null,
        ): Result<BackupManifest> =
            withContext(ioDispatcher) {
                runCatching {
                    val tempZipFile = File(context.cacheDir, "validate_temp.zip")
                    copyUriToTempFile(backupUri, tempZipFile)

                    try {
                        val zipFile = ZipFile(tempZipFile)
                        val password = providedPassword ?: getDecryptedPassword()

                        if (zipFile.isEncrypted) {
                            if (password == null) throw WrongBackupPasswordException()
                            zipFile.setPassword(password.toCharArray())
                        }

                        val header =
                            zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                                ?: throw IllegalStateException("No JSON file found in backup ZIP")

                        zipFile.getInputStream(header).use { inputStream ->
                            val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))
                            var schemaVersion = -1
                            var exportedAt = ""
                            var rowCounts = emptyMap<String, Int>()

                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "schemaVersion" -> schemaVersion = reader.nextInt()
                                    "exportedAt" -> exportedAt = reader.nextString()
                                    "rowCounts" -> {
                                        val counts = mutableMapOf<String, Int>()
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            counts[reader.nextName()] = reader.nextInt()
                                        }
                                        reader.endObject()
                                        rowCounts = counts
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (schemaVersion != HealthDatabase.DATABASE_VERSION) {
                                throw IllegalStateException(
                                    "Backup schema version $schemaVersion does not match database version ${HealthDatabase.DATABASE_VERSION}",
                                )
                            }

                            BackupManifest(schemaVersion, exportedAt, rowCounts)
                        }
                    } finally {
                        tempZipFile.delete()
                    }
                }
            }

        suspend fun applyRestore(
            backupUri: Uri,
            providedPassword: String? = null,
        ): RestoreResult =
            withContext(ioDispatcher) {
                appendAuditBestEffort(
                    AuditEvent(
                        type = AuditEvent.Type.RESTORE_STARTED,
                        occurredAt = Instant.now(),
                        detail = null,
                    ),
                )
                try {
                    val tempZipFile = File(context.cacheDir, "restore_temp.zip")
                    copyUriToTempFile(backupUri, tempZipFile)

                    try {
                        val zipFile = ZipFile(tempZipFile)
                        val password = providedPassword ?: getDecryptedPassword()

                        if (zipFile.isEncrypted) {
                            if (password == null) throw WrongBackupPasswordException()
                            zipFile.setPassword(password.toCharArray())
                        }

                        val header =
                            zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                                ?: throw IllegalStateException("No JSON file found in backup ZIP")

                        var prefsBackup: UserPreferencesBackup? = null
                        healthDatabase.withTransaction {
                            zipFile.getInputStream(header).use { inputStream ->
                                val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))
                                performStreamingRestore(reader) { parsedPreferences ->
                                    prefsBackup = parsedPreferences
                                }
                            }
                        }

                        val backup = prefsBackup
                        if (backup != null) {
                            try {
                                restorePreferences(backup, providedPassword)
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                appendAuditBestEffort(
                                    AuditEvent(
                                        type = AuditEvent.Type.RESTORE_FAILED,
                                        occurredAt = Instant.now(),
                                        detail = "prefs_failed: ${e::class.simpleName}",
                                    ),
                                )
                                return@withContext RestoreResult.PartialSuccessRequiresRestart(
                                    failedStage = RestoreStage.PREFERENCES,
                                    cause = e,
                                )
                            }
                        }

                        appendAuditBestEffort(
                            AuditEvent(
                                type = AuditEvent.Type.RESTORE_COMPLETED,
                                occurredAt = Instant.now(),
                                detail = "success_requires_restart",
                            ),
                        )
                        RestoreResult.SuccessRequiresRestart
                    } finally {
                        tempZipFile.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val cause =
                        if (e is ZipException && e.message?.contains("password", ignoreCase = true) == true) {
                            WrongBackupPasswordException()
                        } else {
                            e
                        }
                    appendAuditBestEffort(
                        AuditEvent(
                            type = AuditEvent.Type.RESTORE_FAILED,
                            occurredAt = Instant.now(),
                            detail = cause::class.simpleName,
                        ),
                    )
                    RestoreResult.Failure(cause)
                }
            }

        private suspend fun appendAuditBestEffort(event: AuditEvent) {
            try {
                auditTrailRepository.append(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("LocalRestoreManager", e) { "Failed to append ${event.type.storageKey} audit event" }
            }
        }

        private suspend fun performStreamingRestore(
            reader: JsonReader,
            onPreferencesParsed: (UserPreferencesBackup) -> Unit,
        ) {
            val sleepSessionDao = healthDatabase.sleepSessionDao()
            val heartRateDao = healthDatabase.heartRateDao()
            val hrvDao = healthDatabase.hrvDao()
            val workoutDao = healthDatabase.workoutDao()
            val dailySummaryDao = healthDatabase.dailySummaryDao()

            // Clear all tables first
            sleepSessionDao.deleteAll()
            heartRateDao.deleteAll()
            hrvDao.deleteAll()
            workoutDao.deleteAll()
            dailySummaryDao.deleteAll()

            reader.beginObject()
            while (reader.hasNext()) {
                when (val name = reader.nextName()) {
                    "preferences" -> {
                        val prefsString = readNextObjectAsString(reader)
                        val prefsBackup = json.decodeFromString<UserPreferencesBackup>(prefsString)
                        onPreferencesParsed(prefsBackup)
                    }
                    "sleepSessions" -> {
                        reader.beginArray()
                        val batch = mutableListOf<SleepSessionEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                sleepSessionDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) sleepSessionDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "heartRateRecords" -> {
                        reader.beginArray()
                        val batch = mutableListOf<HeartRateRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 500) {
                                heartRateDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) heartRateDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "hrvRecords" -> {
                        reader.beginArray()
                        val batch = mutableListOf<HrvRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 500) {
                                hrvDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) hrvDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "workouts" -> {
                        reader.beginArray()
                        val batch = mutableListOf<WorkoutRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                workoutDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) workoutDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "dailySummaries" -> {
                        reader.beginArray()
                        val batch = mutableListOf<DailySummaryEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                dailySummaryDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) dailySummaryDao.upsertAll(batch)
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        private fun readNextObjectAsString(reader: JsonReader): String {
            val sb = StringBuilder()
            parseValue(reader, sb)
            return sb.toString()
        }

        private fun parseValue(
            reader: JsonReader,
            sb: StringBuilder,
        ) {
            when (val token = reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    sb.append("{")
                    var first = true
                    while (reader.hasNext()) {
                        if (!first) sb.append(",")
                        sb.append("\"").append(reader.nextName()).append("\":")
                        parseValue(reader, sb)
                        first = false
                    }
                    reader.endObject()
                    sb.append("}")
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    sb.append("[")
                    var first = true
                    while (reader.hasNext()) {
                        if (!first) sb.append(",")
                        parseValue(reader, sb)
                        first = false
                    }
                    reader.endArray()
                    sb.append("]")
                }
                JsonToken.STRING -> {
                    val s = reader.nextString()
                    sb.append("\"")
                    for (i in 0 until s.length) {
                        val c = s[i]
                        when (c) {
                            '\\' -> sb.append("\\\\")
                            '"' -> sb.append("\\\"")
                            '\n' -> sb.append("\\n")
                            '\r' -> sb.append("\\r")
                            '\t' -> sb.append("\\t")
                            else -> sb.append(c)
                        }
                    }
                    sb.append("\"")
                }
                JsonToken.NUMBER -> {
                    sb.append(reader.nextString())
                }
                JsonToken.BOOLEAN -> {
                    sb.append(reader.nextBoolean())
                }
                JsonToken.NULL -> {
                    reader.nextNull()
                    sb.append("null")
                }
                else -> reader.skipValue()
            }
        }

        private fun copyUriToTempFile(
            uri: Uri,
            tempFile: File,
        ) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Could not open backup URI")
        }

        private suspend fun getDecryptedPassword(): String? {
            val prefs = settingsRepository.userPreferences.first()
            return prefs.backupPasswordHash?.let { hash ->
                encryptionManager.decrypt(hash)
            }
        }

        private suspend fun restorePreferences(
            backup: UserPreferencesBackup,
            providedPassword: String?,
        ) {
            val encryptedProvidedPassword =
                providedPassword
                    ?.takeIf { it.isNotBlank() }
                    ?.let { encryptionManager.encrypt(it) }
            settingsRepository.batchUpdate {
                backup.goalSleepHours?.let { goalSleepHours = it }
                if (backup.hrvBaselineOverride !=
                    null
                ) {
                    hrvBaselineOverride = backup.hrvBaselineOverride
                } else {
                    clearHrvBaselineOverride()
                }
                if (backup.rhrBaselineOverride !=
                    null
                ) {
                    rhrBaselineOverride = backup.rhrBaselineOverride
                } else {
                    clearRhrBaselineOverride()
                }

                backup.syncPreference?.let {
                    try {
                        syncPreference = SyncPreferenceProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        logW("LocalRestoreManager", e) { "Ignoring invalid sync preference in backup settings" }
                    }
                }
                backup.syncIntervalHours?.let { syncIntervalHours = it }
                backup.backgroundSyncEnabled?.let { backgroundSyncEnabled = it }
                backup.backgroundSyncIntervalMinutes?.let { backgroundSyncIntervalMinutes = it }
                backup.lastSyncTimestamp?.let { lastSyncTimestamp = it }
                backup.maxHeartRate?.let { maxHeartRate = it }
                backup.autoCalculateMaxHr?.let { autoCalculateMaxHr = it }
                backup.manualZoneEditing?.let { manualZoneEditing = it }

                if (backup.zone1MinPercent != null &&
                    backup.zone1MaxPercent != null &&
                    backup.zone2MaxPercent != null &&
                    backup.zone3MaxPercent != null &&
                    backup.zone4MaxPercent != null
                ) {
                    zone1MinPercent = backup.zone1MinPercent
                    zone1MaxPercent = backup.zone1MaxPercent
                    zone2MaxPercent = backup.zone2MaxPercent
                    zone3MaxPercent = backup.zone3MaxPercent
                    zone4MaxPercent = backup.zone4MaxPercent
                }

                if (backup.zone1MinBpm != null &&
                    backup.zone1MaxBpm != null &&
                    backup.zone2MaxBpm != null &&
                    backup.zone3MaxBpm != null &&
                    backup.zone4MaxBpm != null
                ) {
                    zone1MinBpm = backup.zone1MinBpm
                    zone1MaxBpm = backup.zone1MaxBpm
                    zone2MaxBpm = backup.zone2MaxBpm
                    zone3MaxBpm = backup.zone3MaxBpm
                    zone4MaxBpm = backup.zone4MaxBpm
                }

                backup.age?.let { age = it }
                // Restore birthDate if available, otherwise fall back to separate fields for backward compatibility
                val parsedDate =
                    backup.birthDate?.let {
                        try {
                            java.time.LocalDate.parse(it)
                        } catch (e: Exception) {
                            null
                        }
                    }
                if (parsedDate != null) {
                    birthDay = parsedDate.dayOfMonth
                    birthMonth = parsedDate.monthValue
                    birthYear = parsedDate.year
                    isBirthdayConfigured = true
                } else if (backup.birthDay != null && backup.birthMonth != null && backup.birthYear != null) {
                    birthDay = backup.birthDay
                    birthMonth = backup.birthMonth
                    birthYear = backup.birthYear
                    isBirthdayConfigured = true
                }

                backup.gender?.let { gender = it } ?: clearGender()
                backup.heightCm?.let { heightCm = it } ?: clearHeightCm()
                backup.hrvOptimalThreshold?.let { hrvOptimalThreshold = it }
                backup.hrvWarningThreshold?.let { hrvWarningThreshold = it }
                backup.rhrOptimalThreshold?.let { rhrOptimalThreshold = it }
                backup.rhrWarningThreshold?.let { rhrWarningThreshold = it }
                backup.restingHrBeforeMinutes?.let { restingHrBeforeMinutes = it }
                backup.restingHrAfterMinutes?.let { restingHrAfterMinutes = it }

                backup.appTheme?.let {
                    try {
                        appTheme = AppThemeProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        logW("LocalRestoreManager", e) { "Ignoring invalid app theme in backup settings" }
                    }
                }

                backup.backupSchedule?.let {
                    try {
                        backupSchedule = BackupScheduleProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        logW("LocalRestoreManager", e) { "Ignoring invalid backup schedule in backup settings" }
                    }
                }

                backup.lastBackupTimestamp?.let { lastBackupTimestamp = it }
                backup.consistencyThresholdMinutes?.let { consistencyThresholdMinutes = it }
                backup.consistencyEvaluationDays?.let { consistencyEvaluationDays = it }
                backup.consistencyBaselineDays?.let { consistencyBaselineDays = it }
                (backup.rasScalingFactor ?: backup.paiScalingFactor)?.let { rasScalingFactor = it }
                backup.stepGoal?.let { stepGoal = it }
                backup.retentionDaysEnabled?.let { retentionDaysEnabled = it }
                backup.retentionDays?.let { retentionDays = it }
                backup.collapseHealthConnect?.let { collapseHealthConnect = it }
                backup.collapseBaselinesThresholds?.let { collapseBaselinesThresholds = it }
                backup.collapseDisplay?.let { collapseDisplay = it }
                backup.collapseAdvanced?.let { collapseAdvanced = it }
                backup.aboutDismissed?.let { aboutDismissed = it }

                backup.physiologyProfile?.let {
                    try {
                        physiologyProfile = PhysiologyProfileProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        logW("LocalRestoreManager", e) { "Ignoring invalid physiology profile in backup settings" }
                    }
                }

                backup.installDate?.let { installDate = it }
                backup.circadianThresholdOverride?.let { circadianThresholdOverride = it }
                    ?: clearCircadianThresholdOverride()
                backup.dynamicColorEnabled?.let { dynamicColorEnabled = it }

                backup.banisterMultiplier?.let { rasCalibration = it }

                backup.trimpModel?.let {
                    try {
                        trimpMethod = TrimpMethodProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        logW("LocalRestoreManager", e) { "Ignoring invalid trimp model in backup settings" }
                    }
                }

                backup.chengBeta?.let { chengBeta = it }
                backup.itrimB?.let { itrimpB = it }
                backup.primaryDeviceName?.let { primaryDeviceName = it }
                backup.deviceByDataType?.let { putAllDeviceByDataType(it) }
                backup.backupDirectoryUri?.let { backupDirectoryUri = it }
                encryptedProvidedPassword?.let { backupPasswordHash = it }
            }
            backup.dashboardCards?.let {
                cardConfigurationRepository.updateDashboardCardConfigurations(it)
            }
            backup.backgroundSyncEnabled?.let { enabled ->
                if (enabled) {
                    backup.backgroundSyncIntervalMinutes?.let { workerScheduler.schedulePeriodicSync(it.toLong()) }
                } else {
                    workerScheduler.cancelPeriodicSync()
                }
            }
            backup.backupSchedule?.let {
                runCatching { BackupScheduleProto.valueOf(it) }
                    .onSuccess { schedule -> workerScheduler.scheduleBackupWorker(schedule.toDomain()) }
            }
        }

        private fun BackupScheduleProto.toDomain() =
            when (this) {
                BackupScheduleProto.BACKUP_MANUAL -> app.readylytics.health.data.preferences.BackupSchedule.MANUAL
                BackupScheduleProto.BACKUP_DAILY -> app.readylytics.health.data.preferences.BackupSchedule.DAILY
                BackupScheduleProto.BACKUP_WEEKLY -> app.readylytics.health.data.preferences.BackupSchedule.WEEKLY
                BackupScheduleProto.UNRECOGNIZED -> app.readylytics.health.data.preferences.BackupSchedule.MANUAL
            }
    }
