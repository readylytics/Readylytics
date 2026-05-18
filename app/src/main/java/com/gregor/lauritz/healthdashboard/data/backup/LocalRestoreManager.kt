package com.gregor.lauritz.healthdashboard.data.backup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import androidx.room.withTransaction
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.json.JSONObject
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
        data class BackupManifest(
            val schemaVersion: Int,
            val exportedAt: String,
            val rowCounts: Map<String, Int>,
        )

        sealed class RestoreResult {
            data object Success : RestoreResult()

            data object SuccessRequiresRestart : RestoreResult()

            data class Failure(
                val cause: Throwable,
            ) : RestoreResult()
        }

        suspend fun validate(backupUri: Uri): Result<BackupManifest> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val tempZipFile = File(context.cacheDir, "validate_temp.zip")
                    copyUriToTempFile(backupUri, tempZipFile)

                    try {
                        val zipFile = ZipFile(tempZipFile)
                        val password = getDecryptedPassword()

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

        suspend fun applyRestore(backupUri: Uri): RestoreResult =
            withContext(Dispatchers.IO) {
                runCatching {
                    val tempZipFile = File(context.cacheDir, "restore_temp.zip")
                    copyUriToTempFile(backupUri, tempZipFile)

                    try {
                        val zipFile = ZipFile(tempZipFile)
                        val password = getDecryptedPassword()

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
                        val prefsJson = readNextObjectAsJson(reader)
                        restorePreferences(prefsJson)
                    }
                    "sleepSessions" -> {
                        reader.beginArray()
                        val batch = mutableListOf<SleepSessionEntity>()
                        while (reader.hasNext()) {
                            batch.add(SleepSessionEntity.fromJson(readNextObjectAsJson(reader)))
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
                            batch.add(HeartRateRecordEntity.fromJson(readNextObjectAsJson(reader)))
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
                            batch.add(HrvRecordEntity.fromJson(readNextObjectAsJson(reader)))
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
                            batch.add(WorkoutRecordEntity.fromJson(readNextObjectAsJson(reader)))
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
                            batch.add(DailySummaryEntity.fromJson(readNextObjectAsJson(reader)))
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

        private fun readNextObjectAsJson(reader: JsonReader): JSONObject {
            // This still creates a JSONObject but only for one record at a time
            val sb = StringBuilder()
            parseValue(reader, sb)
            return JSONObject(sb.toString())
        }

        private fun parseValue(
            reader: JsonReader,
            sb: StringBuilder,
        ) {
            when (val token = reader.peek()) {
                android.util.JsonToken.BEGIN_OBJECT -> {
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
                android.util.JsonToken.BEGIN_ARRAY -> {
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
                android.util.JsonToken.STRING -> {
                    sb.append(org.json.JSONObject.quote(reader.nextString()))
                }
                android.util.JsonToken.NUMBER -> {
                    sb.append(reader.nextString())
                }
                android.util.JsonToken.BOOLEAN -> {
                    sb.append(reader.nextBoolean())
                }
                android.util.JsonToken.NULL -> {
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

        private suspend fun restorePreferences(json: JSONObject) {
            settingsRepository.batchUpdate {
                if (json.has("goalSleepHours")) {
                    goalSleepHours = json.getDouble("goalSleepHours").toFloat()
                }
                if (!json.isNull("hrvBaselineOverride")) {
                    hrvBaselineOverride = json.getDouble("hrvBaselineOverride").toFloat()
                } else if (json.has("hrvBaselineOverride")) {
                    clearHrvBaselineOverride()
                }
                if (!json.isNull("rhrBaselineOverride")) {
                    rhrBaselineOverride = json.getDouble("rhrBaselineOverride").toFloat()
                } else if (json.has("rhrBaselineOverride")) {
                    clearRhrBaselineOverride()
                }
                if (json.has("syncPreference")) {
                    try {
                        syncPreference =
                            com.gregor.lauritz.healthdashboard.data.preferences.SyncPreferenceProto.valueOf(
                                json.getString("syncPreference"),
                            )
                    } catch (e: IllegalArgumentException) {
                        android.util.Log.w(
                            "LocalRestoreManager",
                            "Invalid SyncPreference value: ${json.optString("syncPreference")}",
                            e,
                        )
                    }
                }
                if (json.has("syncIntervalHours")) {
                    syncIntervalHours = json.getInt("syncIntervalHours")
                }
                if (json.has("lastSyncTimestamp")) {
                    lastSyncTimestamp = json.getLong("lastSyncTimestamp")
                }
                if (json.has("maxHeartRate")) {
                    maxHeartRate = json.getInt("maxHeartRate")
                }
                if (json.has("autoCalculateMaxHr")) {
                    autoCalculateMaxHr = json.getBoolean("autoCalculateMaxHr")
                }
                if (json.has("manualZoneEditing")) {
                    manualZoneEditing = json.getBoolean("manualZoneEditing")
                }
                if (json.has("zone1MinPercent") &&
                    json.has("zone1MaxPercent") &&
                    json.has("zone2MaxPercent") &&
                    json.has("zone3MaxPercent") &&
                    json.has("zone4MaxPercent")
                ) {
                    zone1MinPercent = json.getDouble("zone1MinPercent").toFloat()
                    zone1MaxPercent = json.getDouble("zone1MaxPercent").toFloat()
                    zone2MaxPercent = json.getDouble("zone2MaxPercent").toFloat()
                    zone3MaxPercent = json.getDouble("zone3MaxPercent").toFloat()
                    zone4MaxPercent = json.getDouble("zone4MaxPercent").toFloat()
                }
                if (json.has("zone1MinBpm") &&
                    json.has("zone1MaxBpm") &&
                    json.has("zone2MaxBpm") &&
                    json.has("zone3MaxBpm") &&
                    json.has("zone4MaxBpm")
                ) {
                    zone1MinBpm = json.getInt("zone1MinBpm")
                    zone1MaxBpm = json.getInt("zone1MaxBpm")
                    zone2MaxBpm = json.getInt("zone2MaxBpm")
                    zone3MaxBpm = json.getInt("zone3MaxBpm")
                    zone4MaxBpm = json.getInt("zone4MaxBpm")
                }
                if (json.has("age")) {
                    age = json.getInt("age")
                }
                if (json.has("birthDay") && json.has("birthMonth") && json.has("birthYear")) {
                    birthDay = json.getInt("birthDay")
                    birthMonth = json.getInt("birthMonth")
                    birthYear = json.getInt("birthYear")
                }
                if (!json.isNull("gender")) {
                    gender = json.getString("gender")
                } else if (json.has("gender")) {
                    clearGender()
                }
                if (json.has("hrvOptimalThreshold")) {
                    hrvOptimalThreshold = json.getDouble("hrvOptimalThreshold").toFloat()
                }
                if (json.has("hrvWarningThreshold")) {
                    hrvWarningThreshold = json.getDouble("hrvWarningThreshold").toFloat()
                }
                if (json.has("rhrOptimalThreshold")) {
                    rhrOptimalThreshold = json.getDouble("rhrOptimalThreshold").toFloat()
                }
                if (json.has("rhrWarningThreshold")) {
                    rhrWarningThreshold = json.getDouble("rhrWarningThreshold").toFloat()
                }
                if (json.has("restingHrBeforeMinutes")) {
                    restingHrBeforeMinutes = json.getInt("restingHrBeforeMinutes")
                }
                if (json.has("restingHrAfterMinutes")) {
                    restingHrAfterMinutes = json.getInt("restingHrAfterMinutes")
                }
                if (json.has("appTheme")) {
                    try {
                        appTheme =
                            com.gregor.lauritz.healthdashboard.data.preferences.AppThemeProto.valueOf(
                                json.getString("appTheme"),
                            )
                    } catch (e: IllegalArgumentException) {
                        android.util.Log.w(
                            "LocalRestoreManager",
                            "Invalid AppTheme value: ${json.optString("appTheme")}",
                            e,
                        )
                    }
                }
                if (json.has("backupSchedule")) {
                    try {
                        backupSchedule =
                            com.gregor.lauritz.healthdashboard.data.preferences.BackupScheduleProto.valueOf(
                                json.getString("backupSchedule"),
                            )
                    } catch (e: IllegalArgumentException) {
                        android.util.Log.w(
                            "LocalRestoreManager",
                            "Invalid BackupSchedule value: ${json.optString("backupSchedule")}",
                            e,
                        )
                    }
                }
                if (json.has("lastBackupTimestamp")) {
                    lastBackupTimestamp = json.getLong("lastBackupTimestamp")
                }
                if (json.has("consistencyThresholdMinutes")) {
                    consistencyThresholdMinutes = json.getInt("consistencyThresholdMinutes")
                }
                if (json.has("consistencyEvaluationDays")) {
                    consistencyEvaluationDays = json.getInt("consistencyEvaluationDays")
                }
                if (json.has("consistencyBaselineDays")) {
                    consistencyBaselineDays = json.getInt("consistencyBaselineDays")
                }
                if (json.has("paiScalingFactor")) {
                    paiScalingFactor = json.getDouble("paiScalingFactor").toFloat()
                }
                if (json.has("stepGoal")) {
                    stepGoal = json.getInt("stepGoal")
                }
                if (json.has("retentionDaysEnabled")) {
                    retentionDaysEnabled = json.getBoolean("retentionDaysEnabled")
                }
                if (json.has("retentionDays")) {
                    retentionDays = json.getInt("retentionDays")
                }
                if (json.has("collapseCloudData")) {
                    collapseCloudData = json.getBoolean("collapseCloudData")
                }
                if (json.has("collapseHealthConnect")) {
                    collapseHealthConnect = json.getBoolean("collapseHealthConnect")
                }
                if (json.has("collapseBaselinesThresholds")) {
                    collapseBaselinesThresholds = json.getBoolean("collapseBaselinesThresholds")
                }
                if (json.has("collapseDisplay")) {
                    collapseDisplay = json.getBoolean("collapseDisplay")
                }
                if (json.has("collapseAdvanced")) {
                    collapseAdvanced = json.getBoolean("collapseAdvanced")
                }
                if (json.has("aboutDismissed")) {
                    aboutDismissed = json.getBoolean("aboutDismissed")
                }
                if (json.has("physiologyProfile")) {
                    try {
                        physiologyProfile =
                            com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfileProto.valueOf(
                                json.getString("physiologyProfile"),
                            )
                    } catch (e: IllegalArgumentException) {
                        android.util.Log.w(
                            "LocalRestoreManager",
                            "Invalid PhysiologyProfile value: ${json.optString("physiologyProfile")}",
                            e,
                        )
                    }
                }
                if (json.has("installDate")) {
                    installDate = json.getLong("installDate")
                }
                if (!json.isNull("circadianThresholdOverride")) {
                    circadianThresholdOverride = json.getString("circadianThresholdOverride")
                } else if (json.has("circadianThresholdOverride")) {
                    clearCircadianThresholdOverride()
                }
                if (json.has("dynamicColorEnabled")) {
                    dynamicColorEnabled = json.getBoolean("dynamicColorEnabled")
                }
                if (json.has("trimpMethod")) {
                    try {
                        trimpMethod =
                            com.gregor.lauritz.healthdashboard.data.preferences.TrimpMethodProto.valueOf(
                                json.getString("trimpMethod"),
                            )
                    } catch (e: IllegalArgumentException) {
                        android.util.Log.w(
                            "LocalRestoreManager",
                            "Invalid TrimpMethod value: ${json.optString("trimpMethod")}",
                            e,
                        )
                    }
                }
                if (json.has("chengBeta")) {
                    chengBeta = json.getDouble("chengBeta").toFloat()
                }
                if (json.has("itrimB")) {
                    itrimpB = json.getDouble("itrimB").toFloat()
                }
                if (!json.isNull("primaryDeviceName")) {
                    primaryDeviceName = json.getString("primaryDeviceName")
                } else if (json.has("primaryDeviceName")) {
                    clearPrimaryDeviceName()
                }
                if (!json.isNull("backupDirectoryUri")) {
                    backupDirectoryUri = json.getString("backupDirectoryUri")
                } else if (json.has("backupDirectoryUri")) {
                    clearBackupDirectoryUri()
                }
            }
        }
    }
