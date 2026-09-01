package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import androidx.room.withTransaction
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.backup.RestoreStage
import app.readylytics.health.core.model.domain.backup.WrongBackupPasswordException
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
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

@Singleton
class LocalRestoreManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val healthDatabase: HealthDatabase,
        private val settingsRepository: SettingsRepository,
        private val batchLoader: RestoreBatchLoader,
        private val restorePrefsApplier: RestorePreferencesApplier,
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
                        val password =
                            providedPassword
                                ?.takeIf { it.isNotBlank() }
                                ?: settingsRepository.userPreferences.first().backupPasswordHash?.let {
                                    encryptionManager
                                        .decrypt(
                                            it,
                                        )
                                }

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
                logAudit(AuditEvent.Type.RESTORE_STARTED)
                try {
                    val tempZipFile = File(context.cacheDir, "restore_temp.zip")
                    copyUriToTempFile(backupUri, tempZipFile)
                    try {
                        performRestoreWithZip(tempZipFile, providedPassword)
                    } finally {
                        tempZipFile.delete()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ZipException) {
                    val cause =
                        if (e.message?.contains("password", ignoreCase = true) == true) {
                            WrongBackupPasswordException()
                        } else {
                            e
                        }
                    buildRestoreFailure(cause)
                } catch (e: Throwable) {
                    buildRestoreFailure(e)
                }
            }

        private suspend fun performRestoreWithZip(
            tempZipFile: File,
            providedPassword: String?,
        ): RestoreResult {
            val zipFile = ZipFile(tempZipFile)
            val password =
                providedPassword
                    ?.takeIf { it.isNotBlank() }
                    ?: settingsRepository.userPreferences.first().backupPasswordHash?.let {
                        encryptionManager.decrypt(
                            it,
                        )
                    }

            if (zipFile.isEncrypted) {
                if (password == null) throw WrongBackupPasswordException()
                zipFile.setPassword(password.toCharArray())
            }

            val prefsBackup = readManifestAndStream(zipFile)

            if (prefsBackup != null) {
                try {
                    restorePrefsApplier.restorePreferences(prefsBackup, providedPassword)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logAudit(AuditEvent.Type.RESTORE_FAILED, "prefs_failed: ${e::class.simpleName}")
                    return RestoreResult.PartialSuccessRequiresRestart(
                        failedStage = RestoreStage.PREFERENCES,
                        cause = e,
                    )
                }
            }

            logAudit(AuditEvent.Type.RESTORE_COMPLETED, "success_requires_restart")
            return RestoreResult.SuccessRequiresRestart
        }

        private suspend fun logAudit(
            type: AuditEvent.Type,
            detail: String? = null,
        ) {
            auditTrailRepository.appendBestEffort(
                "LocalRestoreManager",
                AuditEvent(
                    type = type,
                    occurredAt = Instant.now(),
                    detail = detail,
                ),
            )
        }

        private suspend fun readManifestAndStream(zipFile: ZipFile): UserPreferencesBackup? {
            val manifest = readManifest(zipFile)
            val header =
                zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                    ?: error("No JSON file found in backup ZIP")

            var prefsBackup: UserPreferencesBackup? = null
            healthDatabase.withTransaction {
                zipFile.getInputStream(header).use { inputStream ->
                    val reader = JsonReader(InputStreamReader(inputStream, "UTF-8"))
                    performStreamingRestore(reader, manifest.schemaVersion) { parsedPreferences ->
                        prefsBackup = parsedPreferences
                    }
                }
            }
            return prefsBackup
        }

        private suspend fun buildRestoreFailure(cause: Throwable): RestoreResult.Failure {
            logAudit(AuditEvent.Type.RESTORE_FAILED, cause::class.simpleName)
            return RestoreResult.Failure(cause)
        }

        private fun readManifest(zipFile: ZipFile): BackupManifest {
            val header =
                zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                    ?: error("No JSON file found in backup ZIP")

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
                        "rowCounts" -> rowCounts = readRowCounts(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                BackupSchemaPolicy.requireSupported(schemaVersion)
                BackupManifest(schemaVersion, exportedAt, rowCounts)
            }
        }

        private fun readRowCounts(reader: JsonReader): Map<String, Int> {
            val counts = mutableMapOf<String, Int>()
            reader.beginObject()
            while (reader.hasNext()) {
                counts[reader.nextName()] = reader.nextInt()
            }
            reader.endObject()
            return counts
        }

        private suspend fun performStreamingRestore(
            reader: JsonReader,
            schemaVersion: Int,
            onPreferencesParsed: (UserPreferencesBackup) -> Unit,
        ) {
            healthDatabase.sleepSessionDao().deleteAll()
            healthDatabase.heartRateDao().deleteAll()
            healthDatabase.hrvDao().deleteAll()
            healthDatabase.workoutDao().deleteAll()
            healthDatabase.dailySummaryDao().deleteAll()
            healthDatabase.sourceRecordDao().deleteAll()
            healthDatabase.minuteBucketMaintenanceDao().deleteAll()

            val handlers =
                mapOf<String, suspend (JsonReader) -> Unit>(
                    "sleepSessions" to { batchLoader.restoreSleepSessions(it) },
                    "healthSourceRecords" to { batchLoader.restoreHealthSourceRecords(it) },
                    "heartRateRecords" to { batchLoader.restoreHeartRateRecords(it, schemaVersion) },
                    "hrvRecords" to { batchLoader.restoreHrvRecords(it, schemaVersion) },
                    "hrMinuteBuckets" to { batchLoader.restoreHrMinuteBuckets(it) },
                    "workouts" to { batchLoader.restoreWorkouts(it) },
                    "workoutRoutePoints" to { batchLoader.restoreWorkoutRoutePoints(it) },
                    "dailySummaries" to { batchLoader.restoreDailySummaries(it) },
                    "weightRecords" to { batchLoader.vitalsLoader.restoreWeightRecords(it) },
                    "bodyFatRecords" to { batchLoader.vitalsLoader.restoreBodyFatRecords(it) },
                    "bloodPressureRecords" to { batchLoader.vitalsLoader.restoreBloodPressureRecords(it) },
                    "oxygenSaturationRecords" to { batchLoader.vitalsLoader.restoreOxygenSaturationRecords(it) },
                    "bodyTemperatureRecords" to { batchLoader.vitalsLoader.restoreBodyTemperatureRecords(it) },
                    "stepRecords" to { batchLoader.vitalsLoader.restoreStepRecords(it) },
                )

            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                when {
                    key == "preferences" -> {
                        val prefsString = readNextObjectAsString(json, reader)
                        val prefsBackup = json.decodeFromString<UserPreferencesBackup>(prefsString)
                        onPreferencesParsed(prefsBackup)
                    }
                    key in handlers -> handlers[key]!!.invoke(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        private fun copyUriToTempFile(
            uri: Uri,
            tempFile: File,
        ) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Could not open backup URI")
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
