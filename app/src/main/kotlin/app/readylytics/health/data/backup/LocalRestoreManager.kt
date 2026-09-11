package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
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
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRestoreManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val restoreDatabaseOperations: RestoreDatabaseOperations,
        private val restorePrefsApplier: RestorePreferencesApplier,
        private val encryptionManager: EncryptionManager,
        private val auditTrailRepository: AuditTrailRepository,
        private val recommendationCoverageChecker: RestoreRecommendationCoverageChecker,
        private val inventoryValidator: RestoreInventoryValidator,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
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
                                    encryptionManager.decrypt(it)
                                }

                        if (zipFile.isEncrypted) {
                            if (password == null) throw WrongBackupPasswordException()
                            zipFile.setPassword(password.toCharArray())
                        }

                        val header =
                            zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                                ?: error("No JSON file found in backup ZIP")

                        val validated =
                            zipFile.getInputStream(header).use { inputStream ->
                                inventoryValidator.validate(inputStream)
                            }
                        validated.manifest
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
                        encryptionManager.decrypt(it)
                    }

            if (zipFile.isEncrypted) {
                if (password == null) throw WrongBackupPasswordException()
                zipFile.setPassword(password.toCharArray())
            }

            val header =
                zipFile.fileHeaders.firstOrNull { it.fileName.endsWith(".json") }
                    ?: error("No JSON file found in backup ZIP")

            // Read-only streaming validation pass before replacing any database data
            val validated =
                zipFile.getInputStream(header).use { inputStream ->
                    inventoryValidator.validate(inputStream)
                }

            val prefsBackup =
                restoreDatabaseOperations.executeDatabaseRestore(
                    zipFile = zipFile,
                    header = header,
                    validated = validated,
                )

            if (prefsBackup != null) {
                try {
                    restorePrefsApplier.restorePreferences(prefsBackup, providedPassword)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logAudit(AuditEvent.Type.RESTORE_FAILED, "prefs_failed: ${e::class.simpleName}")
                    recommendationCoverageChecker.scheduleRecomputeIfIncomplete()
                    return RestoreResult.PartialSuccessRequiresRestart(
                        failedStage = RestoreStage.PREFERENCES,
                        cause = e,
                    )
                }
            }

            recommendationCoverageChecker.scheduleRecomputeIfIncomplete()

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

        private suspend fun buildRestoreFailure(cause: Throwable): RestoreResult.Failure {
            logAudit(AuditEvent.Type.RESTORE_FAILED, cause::class.simpleName)
            return RestoreResult.Failure(cause)
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
