package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.backup.RestoreStage
import app.readylytics.health.core.model.domain.backup.WrongBackupPasswordException
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.util.logW
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.preferences.AppThemeProto
import app.readylytics.health.data.preferences.BackupScheduleProto
import app.readylytics.health.data.preferences.PhysiologyProfileProto
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SleepScoreWeightProfileProto
import app.readylytics.health.data.preferences.SyncPreferenceProto
import app.readylytics.health.data.preferences.TrimpMethodProto
import app.readylytics.health.data.preferences.WorkoutDetailLayoutMapper
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File
import java.io.InputStreamReader
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRestoreManager
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
        private val workerScheduler: WorkerScheduler,
        private val encryptionManager: EncryptionManager,
        private val auditTrailRepository: AuditTrailRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

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

                        readManifest(zipFile)
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
                auditTrailRepository.appendBestEffort(
                    "LocalRestoreManager",
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

                        val manifest = readManifest(zipFile)
                        val header =
                            zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                                ?: throw IllegalStateException("No JSON file found in backup ZIP")

                        var prefsBackup: UserPreferencesBackup? = null
                        healthDatabase.withTransaction {
                            zipFile.getInputStream(header).use { inputStream ->
                                val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))
                                performStreamingRestore(reader, manifest.schemaVersion) { parsedPreferences ->
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
                                auditTrailRepository.appendBestEffort(
                                    "LocalRestoreManager",
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

                        auditTrailRepository.appendBestEffort(
                            "LocalRestoreManager",
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
                    auditTrailRepository.appendBestEffort(
                        "LocalRestoreManager",
                        AuditEvent(
                            type = AuditEvent.Type.RESTORE_FAILED,
                            occurredAt = Instant.now(),
                            detail = cause::class.simpleName,
                        ),
                    )
                    RestoreResult.Failure(cause)
                }
            }

        private fun readManifest(zipFile: ZipFile): BackupManifest {
            val header =
                zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                    ?: throw IllegalStateException("No JSON file found in backup ZIP")

            return zipFile.getInputStream(header).use { inputStream ->
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

                BackupSchemaPolicy.requireSupported(schemaVersion)
                BackupManifest(schemaVersion, exportedAt, rowCounts)
            }
        }

        private suspend fun performStreamingRestore(
            reader: JsonReader,
            schemaVersion: Int,
            onPreferencesParsed: (UserPreferencesBackup) -> Unit,
        ) {
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

            // Clear all tables first
            sleepSessionDao.deleteAll()
            heartRateDao.deleteAll()
            hrvDao.deleteAll()
            workoutDao.deleteAll()
            dailySummaryDao.deleteAll()
            sourceRecordDao.deleteAll()
            minuteBucketDao.deleteAll()

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
                    "healthSourceRecords" -> {
                        reader.beginArray()
                        val batch = mutableListOf<HealthSourceRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 500) {
                                sourceRecordDao.insertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) sourceRecordDao.insertAll(batch)
                        reader.endArray()
                    }
                    "heartRateRecords" -> {
                        reader.beginArray()
                        val batch = mutableListOf<HeartRateRecordEntity>()
                        while (reader.hasNext()) {
                            val row = readNextObjectAsString(reader)
                            val entity = decodeHeartRateRecord(row, schemaVersion, sourceRecordDao)
                            batch.add(entity)
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
                            val row = readNextObjectAsString(reader)
                            val entity = decodeHrvRecord(row, schemaVersion, sourceRecordDao)
                            batch.add(entity)
                            if (batch.size >= 500) {
                                hrvDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) hrvDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "hrMinuteBuckets" -> {
                        reader.beginArray()
                        val batch = mutableListOf<HrMinuteBucketEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 500) {
                                minuteBucketDao.upsertBuckets(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) minuteBucketDao.upsertBuckets(batch)
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
                    // Exported after "workouts", so the parent rows the FK points at already
                    // exist by the time this branch runs. workoutDao.deleteAll() above already
                    // cascaded the old rows away, so no separate clear is needed here.
                    "workoutRoutePoints" -> {
                        reader.beginArray()
                        val batch = mutableListOf<WorkoutRoutePointEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 500) {
                                workoutRoutePointDao.insertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) workoutRoutePointDao.insertAll(batch)
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
                    // The six raw-vitals tables below predate this key existing in the export
                    // (see the backup export task). Each one's deleteAll() is scoped inside its
                    // own branch -- it only fires when the key is actually present -- so restoring
                    // an older backup that has none of these keys leaves the current local rows
                    // for these tables untouched instead of silently wiping them.
                    "weightRecords" -> {
                        weightRecordDao.deleteAll()
                        reader.beginArray()
                        val batch = mutableListOf<WeightRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                weightRecordDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) weightRecordDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "bodyFatRecords" -> {
                        bodyFatRecordDao.deleteAll()
                        reader.beginArray()
                        val batch = mutableListOf<BodyFatRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                bodyFatRecordDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) bodyFatRecordDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "bloodPressureRecords" -> {
                        bloodPressureRecordDao.deleteAll()
                        reader.beginArray()
                        val batch = mutableListOf<BloodPressureRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                bloodPressureRecordDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) bloodPressureRecordDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "oxygenSaturationRecords" -> {
                        oxygenSaturationRecordDao.deleteAll()
                        reader.beginArray()
                        val batch = mutableListOf<OxygenSaturationRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                oxygenSaturationRecordDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) oxygenSaturationRecordDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "bodyTemperatureRecords" -> {
                        bodyTemperatureRecordDao.deleteAll()
                        reader.beginArray()
                        val batch = mutableListOf<BodyTemperatureRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 100) {
                                bodyTemperatureRecordDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) bodyTemperatureRecordDao.upsertAll(batch)
                        reader.endArray()
                    }
                    "stepRecords" -> {
                        stepRecordDao.deleteAll()
                        reader.beginArray()
                        val batch = mutableListOf<StepRecordEntity>()
                        while (reader.hasNext()) {
                            batch.add(json.decodeFromString(readNextObjectAsString(reader)))
                            if (batch.size >= 500) {
                                stepRecordDao.upsertAll(batch)
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) stepRecordDao.upsertAll(batch)
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        private suspend fun decodeHeartRateRecord(
            row: String,
            schemaVersion: Int,
            sourceRecordDao: SourceRecordDao,
        ): HeartRateRecordEntity =
            when {
                schemaVersion >= BackupSchemaPolicy.SOURCE_REF_FORMAT_MIN_VERSION ->
                    json.decodeFromString<HeartRateRecordEntity>(row)
                schemaVersion >= BackupSchemaPolicy.CURRENT_RECORD_FORMAT_MIN_VERSION -> {
                    val backup = json.decodeFromString<SourceRecordIdHeartRateRecordBackup>(row)
                    HeartRateRecordEntity(
                        sourceRecordRef =
                            sourceRecordDao.getOrCreateSourceRef(
                                sourceRecordId = backup.sourceRecordId.substringBefore('_'),
                                recordType = "HEART_RATE",
                                createdAtMs = backup.timestampMs,
                            ),
                        timestampMs = backup.timestampMs,
                        beatsPerMinute = backup.beatsPerMinute,
                        recordType = backup.recordType,
                        sessionId = backup.sessionId,
                        deviceName = backup.deviceName,
                    )
                }
                else -> {
                    val backup = json.decodeFromString<LegacyHeartRateRecordBackup>(row)
                    HeartRateRecordEntity(
                        sourceRecordRef =
                            sourceRecordDao.getOrCreateSourceRef(
                                sourceRecordId = backup.toSourceRecordId(),
                                recordType = "HEART_RATE",
                                createdAtMs = backup.timestampMs,
                            ),
                        timestampMs = backup.timestampMs,
                        beatsPerMinute = backup.beatsPerMinute,
                        recordType = backup.recordType,
                        sessionId = backup.sessionId,
                        deviceName = backup.deviceName,
                    )
                }
            }

        private suspend fun decodeHrvRecord(
            row: String,
            schemaVersion: Int,
            sourceRecordDao: SourceRecordDao,
        ): HrvRecordEntity =
            when {
                schemaVersion >= BackupSchemaPolicy.SOURCE_REF_FORMAT_MIN_VERSION ->
                    json.decodeFromString<HrvRecordEntity>(row)
                schemaVersion >= BackupSchemaPolicy.CURRENT_RECORD_FORMAT_MIN_VERSION -> {
                    val backup = json.decodeFromString<SourceRecordIdHrvRecordBackup>(row)
                    HrvRecordEntity(
                        sourceRecordRef =
                            sourceRecordDao.getOrCreateSourceRef(
                                sourceRecordId = backup.sourceRecordId.substringBefore('_'),
                                recordType = "HRV",
                                createdAtMs = backup.timestampMs,
                            ),
                        timestampMs = backup.timestampMs,
                        rmssdMs = backup.rmssdMs,
                        recordType = backup.recordType,
                        sessionId = backup.sessionId,
                        deviceName = backup.deviceName,
                    )
                }
                else -> {
                    val backup = json.decodeFromString<LegacyHrvRecordBackup>(row)
                    HrvRecordEntity(
                        sourceRecordRef =
                            sourceRecordDao.getOrCreateSourceRef(
                                sourceRecordId = backup.toSourceRecordId(),
                                recordType = "HRV",
                                createdAtMs = backup.timestampMs,
                            ),
                        timestampMs = backup.timestampMs,
                        rmssdMs = backup.rmssdMs,
                        recordType = backup.recordType,
                        sessionId = backup.sessionId,
                        deviceName = backup.deviceName,
                    )
                }
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
                        sb.append(json.encodeToString(reader.nextName())).append(":")
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
                    sb.append(json.encodeToString(reader.nextString()))
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

                backup.syncPreference?.let { raw ->
                    val resolved = resolveProtoEnum(raw, "SYNC_", SyncPreferenceProto::valueOf)
                    if (resolved != null) {
                        syncPreference = resolved
                    } else {
                        logW(
                            "LocalRestoreManager",
                        ) { "Ignoring unrecognised sync preference '$raw' in backup settings" }
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
                        } catch (e: java.time.format.DateTimeParseException) {
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
                backup.hrrToleranceSeconds?.let {
                    hrrToleranceSeconds =
                        it.coerceIn(
                            SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS,
                            SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS,
                        )
                }
                backup.restingHrBeforeMinutes?.let { restingHrBeforeMinutes = it }
                backup.restingHrAfterMinutes?.let { restingHrAfterMinutes = it }

                backup.appTheme?.let { raw ->
                    val resolved = resolveProtoEnum(raw, "THEME_", AppThemeProto::valueOf)
                    if (resolved != null) {
                        appTheme = resolved
                    } else {
                        logW("LocalRestoreManager") { "Ignoring unrecognised app theme '$raw' in backup settings" }
                    }
                }

                backup.backupSchedule?.let { raw ->
                    val resolved = resolveProtoEnum(raw, "BACKUP_", BackupScheduleProto::valueOf)
                    if (resolved != null) {
                        backupSchedule = resolved
                    } else {
                        logW(
                            "LocalRestoreManager",
                        ) { "Ignoring unrecognised backup schedule '$raw' in backup settings" }
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

                backup.physiologyProfile?.let { raw ->
                    val resolved = resolveProtoEnum(raw, "PROFILE_", PhysiologyProfileProto::valueOf)
                    if (resolved != null) {
                        physiologyProfile = resolved
                    } else {
                        logW(
                            "LocalRestoreManager",
                        ) { "Ignoring unrecognised physiology profile '$raw' in backup settings" }
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
                backup.sleepScoreWeightProfile?.let { raw ->
                    val resolved = resolveProtoEnum(raw, "SLEEP_WEIGHT_PROFILE_", SleepScoreWeightProfileProto::valueOf)
                    if (resolved != null) {
                        sleepScoreWeightProfile = resolved
                    } else {
                        logW(
                            "LocalRestoreManager",
                        ) { "Ignoring unrecognised sleep score weight profile '$raw' in backup settings" }
                    }
                }
                backup.hypersomniaOnsetPercent?.let { hypersomniaOnsetPercent = it }
                backup.scoringVersion?.let { scoringVersion = it }
                backup.lastRecalcSleepScoreWeightProfile?.let { raw ->
                    val resolved =
                        resolveProtoEnum(raw, "SLEEP_WEIGHT_PROFILE_", SleepScoreWeightProfileProto::valueOf)
                    if (resolved != null) {
                        lastRecalcSleepScoreWeightProfile = resolved
                    } else {
                        logW(
                            "LocalRestoreManager",
                        ) { "Ignoring unrecognised lastRecalcSleepScoreWeightProfile '$raw' in backup settings" }
                    }
                }
                backup.lastRecalcGoalSleepHours?.let { lastRecalcGoalSleepHours = it }
                backup.lastRecalcHypersomniaOnsetPercent?.let { lastRecalcHypersomniaOnsetPercent = it }
                encryptedProvidedPassword?.let { backupPasswordHash = it }
            }
            backup.dashboardCards?.let {
                cardConfigurationRepository.updateDashboardCardConfigurations(it)
            }
            backup.vitalsCards?.let {
                vitalsLayoutRepository.updateVitalsCardConfigurations(it)
            }
            backup.vitalsCharts?.let {
                vitalsLayoutRepository.updateVitalsChartConfigurations(it)
            }
            backup.sleepTopCards?.let {
                sleepLayoutRepository.updateSleepTopCardConfigurations(it)
            }
            backup.sleepCharts?.let {
                sleepLayoutRepository.updateSleepChartConfigurations(it)
            }
            backup.sleepMetricCards?.let {
                sleepLayoutRepository.updateSleepMetricCardConfigurations(it)
            }
            backup.workoutCards?.let {
                workoutsLayoutRepository.updateWorkoutCardConfigurations(it)
            }
            backup.workoutCharts?.let {
                workoutsLayoutRepository.updateWorkoutChartConfigurations(it)
            }
            backup.workoutHistory?.let {
                workoutsLayoutRepository.updateWorkoutHistoryConfigurations(it)
            }
            backup.workoutDetailLayouts?.let { layouts ->
                workoutDetailLayoutRepository.replaceAll(
                    layouts
                        .mapNotNull { (key, items) ->
                            val type = WorkoutDetailLayoutMapper.typeFromKey(key)
                            if (type == null) {
                                logW("LocalRestoreManager") {
                                    "Ignoring unknown workout layout type in backup: $key"
                                }
                                null
                            } else {
                                type to items
                            }
                        }.toMap(),
                )
            }
            backup.backgroundSyncEnabled?.let { enabled ->
                if (enabled) {
                    backup.backgroundSyncIntervalMinutes?.let { workerScheduler.schedulePeriodicSync(it.toLong()) }
                } else {
                    workerScheduler.cancelPeriodicSync()
                }
            }
            backup.backupSchedule?.let { raw ->
                resolveProtoEnum(raw, "BACKUP_", BackupScheduleProto::valueOf)
                    ?.let { schedule -> workerScheduler.scheduleBackupWorker(schedule.toDomain()) }
            }
        }

        private fun BackupScheduleProto.toDomain() =
            when (this) {
                BackupScheduleProto.BACKUP_MANUAL -> BackupSchedule.MANUAL
                BackupScheduleProto.BACKUP_DAILY -> BackupSchedule.DAILY
                BackupScheduleProto.BACKUP_WEEKLY -> BackupSchedule.WEEKLY
                BackupScheduleProto.UNRECOGNIZED -> BackupSchedule.MANUAL
            }
    }

/**
 * Resolves a proto enum constant from the name a backup actually stores.
 *
 * Backups store the **domain** enum name (`BY_TIME`, `DARK`, `DAILY`, `ATHLETE`) while the proto
 * enums are prefixed (`SYNC_BY_TIME`, `THEME_DARK`, `BACKUP_DAILY`, `PROFILE_ATHLETE`). A plain
 * `Proto.valueOf(raw)` therefore threw for *every* value, and the caller's catch quietly reset the
 * preference to its default — silently losing the user's sync mode, theme, backup schedule and,
 * most importantly, physiology profile, which feeds `snapshotProfile`/`hrvSigmaPrior` in the
 * scoring engine.
 *
 * Resolving here rather than fixing the writer is deliberate: backups already written by released
 * versions contain the unprefixed form and must keep restoring correctly. The prefixed branch
 * covers any backup that stores the proto name instead.
 *
 * Internal rather than private so RestorePreferenceEnumRoundTripTest exercises this exact
 * implementation instead of a copy that could drift from it.
 */
internal fun <T> resolveProtoEnum(
    raw: String,
    prefix: String,
    valueOf: (String) -> T,
): T? =
    runCatching { valueOf(raw) }.getOrNull()
        ?: runCatching { valueOf(prefix + raw) }.getOrNull()
