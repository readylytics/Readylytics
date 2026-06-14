package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

class WrongBackupPasswordException : Exception("Incorrect backup password")

@Singleton
class LocalRestoreManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val encryptionManager: EncryptionManager,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        sealed class RestoreResult {
            data object Success : RestoreResult()

            data object SuccessRequiresRestart : RestoreResult()

            data class Failure(
                val cause: Throwable,
            ) : RestoreResult()
        }

        suspend fun validate(
            backupUri: Uri,
            providedPassword: String? = null,
        ): Result<BackupManifest> =
            withContext(Dispatchers.IO) {
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
            withContext(Dispatchers.IO) {
                runCatching {
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

                        healthDatabase.withTransaction {
                            zipFile.getInputStream(header).use { inputStream ->
                                val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))
                                performStreamingRestore(reader)
                            }
                        }

                        RestoreResult.SuccessRequiresRestart
                    } finally {
                        tempZipFile.delete()
                    }
                }.getOrElse {
                    if (it is ZipException && it.message?.contains("password", ignoreCase = true) == true) {
                        RestoreResult.Failure(WrongBackupPasswordException())
                    } else {
                        RestoreResult.Failure(it)
                    }
                }
            }

        private suspend fun performStreamingRestore(reader: JsonReader) {
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
                        restorePreferences(prefsBackup)
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
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: throw IllegalStateException("Could not open backup URI")
            tempFile.writeBytes(bytes)
        }

        private suspend fun getDecryptedPassword(): String? {
            val prefs = settingsRepository.userPreferences.first()
            return prefs.backupPasswordHash?.let { hash ->
                encryptionManager.decrypt(hash)
            }
        }

        private suspend fun restorePreferences(backup: UserPreferencesBackup) {
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
                        Log.w("LocalRestoreManager", "Invalid SyncPreference: $it", e)
                    }
                }
                backup.syncIntervalHours?.let { syncIntervalHours = it }
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
                        Log.w("LocalRestoreManager", "Invalid AppTheme: $it", e)
                    }
                }

                backup.backupSchedule?.let {
                    try {
                        backupSchedule = BackupScheduleProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        Log.w("LocalRestoreManager", "Invalid BackupSchedule: $it", e)
                    }
                }

                backup.lastBackupTimestamp?.let { lastBackupTimestamp = it }
                backup.consistencyThresholdMinutes?.let { consistencyThresholdMinutes = it }
                backup.consistencyEvaluationDays?.let { consistencyEvaluationDays = it }
                backup.consistencyBaselineDays?.let { consistencyBaselineDays = it }
                backup.paiScalingFactor?.let { paiScalingFactor = it }
                backup.stepGoal?.let { stepGoal = it }
                backup.retentionDaysEnabled?.let { retentionDaysEnabled = it }
                backup.retentionDays?.let { retentionDays = it }
                backup.collapseCloudData?.let { collapseCloudData = it }
                backup.collapseHealthConnect?.let { collapseHealthConnect = it }
                backup.collapseBaselinesThresholds?.let { collapseBaselinesThresholds = it }
                backup.collapseDisplay?.let { collapseDisplay = it }
                backup.collapseAdvanced?.let { collapseAdvanced = it }
                backup.aboutDismissed?.let { aboutDismissed = it }

                backup.physiologyProfile?.let {
                    try {
                        physiologyProfile = PhysiologyProfileProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        Log.w("LocalRestoreManager", "Invalid PhysiologyProfile: $it", e)
                    }
                }

                backup.installDate?.let { installDate = it }
                backup.circadianThresholdOverride?.let { circadianThresholdOverride = it }
                    ?: clearCircadianThresholdOverride()
                backup.dynamicColorEnabled?.let { dynamicColorEnabled = it }

                backup.banisterMultiplier?.let { paiCalibration = it }

                backup.trimpModel?.let {
                    try {
                        trimpMethod = TrimpMethodProto.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        Log.w("LocalRestoreManager", "Invalid TrimpModel: $it", e)
                    }
                }

                backup.chengBeta?.let { chengBeta = it }
                backup.itrimB?.let { itrimpB = it }
                backup.primaryDeviceName?.let { primaryDeviceName = it }
                backup.deviceByDataType?.let { putAllDeviceByDataType(it) }
                backup.backupDirectoryUri?.let { backupDirectoryUri = it }
            }
        }
    }
