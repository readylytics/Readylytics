package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.backup.ArchiveRotationEntry
import app.readylytics.health.core.model.domain.backup.BackupFileInfo
import app.readylytics.health.core.model.domain.backup.BackupLocation
import app.readylytics.health.core.model.domain.backup.BackupOperationPhase
import app.readylytics.health.core.model.domain.backup.BackupOperationState
import app.readylytics.health.core.model.domain.preferences.BackupSettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.model.domain.util.logE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class BackupRotationService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val userPreferencesReader: UserPreferencesReader,
        private val backupSettings: BackupSettings,
        private val encryptionManager: EncryptionManager,
        private val backupStoreFactory: BackupStoreFactory,
        private val journal: BackupOperationJournal,
        private val verifiedPublisher: VerifiedArchivePublisher,
        private val auditTrailRepository: AuditTrailRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val mutex = Mutex()
        private val _operationState = MutableStateFlow(BackupOperationState())
        val operationState: StateFlow<BackupOperationState> = _operationState.asStateFlow()

        suspend fun rotatePassword(newPassword: String): Result<Unit> =
            withContext(ioDispatcher) {
                mutex.withLock {
                    try {
                        val prefs = userPreferencesReader.userPreferences.first()
                        val store = backupStoreFactory.create(prefs.backupDirectoryUri?.toUri())
                        recoverPreviousJournalIfAny(store)

                        val oldHash = prefs.backupPasswordHash
                        val oldPassword = oldHash?.let { encryptionManager.decrypt(it) }
                        if (oldHash != null && oldPassword == null) {
                            val ex = IllegalStateException("Cannot decrypt current backup password")
                            recordAuditEvent(AuditEvent.Type.KEY_ROTATION_FAILED, ex::class.simpleName)
                            return@withLock Result.failure(ex)
                        }

                        val newHash = if (newPassword.isBlank()) null else encryptionManager.encrypt(newPassword)
                        val backups = store.list()
                        if (backups.isEmpty()) {
                            backupSettings.updateBackupPasswordHash(newHash)
                            _operationState.value = BackupOperationState(phase = BackupOperationPhase.COMPLETE)
                            recordAuditEvent(AuditEvent.Type.KEY_ROTATED, null)
                            return@withLock Result.success(Unit)
                        }

                        executeRotation(
                            store,
                            backups,
                            oldHash,
                            oldPassword,
                            newHash,
                            newPassword,
                            prefs.backupDirectoryUri,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logE("BackupRotationService", e) { "rotatePassword failed" }
                        recordAuditEvent(AuditEvent.Type.KEY_ROTATION_FAILED, e::class.simpleName)
                        Result.failure(e)
                    }
                }
            }

        private suspend fun executeRotation(
            store: BackupStore,
            backups: List<BackupFileInfo>,
            oldHash: String?,
            oldPassword: String?,
            newHash: String?,
            newPassword: String,
            directoryUri: String?,
        ): Result<Unit> {
            val operationId = "rotation_${UUID.randomUUID()}"
            val stagingDir = File(context.cacheDir, "backup-rotation/$operationId").apply { mkdirs() }
            var entries = backups.map { ArchiveRotationEntry(originalLocation = it.location.value) }

            var journalData =
                RotationJournalData(
                    protocolVersion = 1,
                    operationId = operationId,
                    directoryUri = directoryUri,
                    phase = BackupOperationPhase.PREPARING,
                    encryptedOldPassword = oldHash,
                    encryptedNewPassword = newHash,
                    targetPasswordHash = newHash,
                    entries = entries,
                    selectedGeneration = System.currentTimeMillis(),
                )
            journal.write(journalData)
            _operationState.value =
                BackupOperationState(
                    operationId = operationId,
                    phase = BackupOperationPhase.PREPARING,
                    completedArchives = 0,
                    totalArchives = entries.size,
                )

            return try {
                entries = stageArchives(store, entries, stagingDir, oldPassword, newPassword)
                journalData = journalData.copy(phase = BackupOperationPhase.STAGED, entries = entries)
                journal.write(journalData)
                _operationState.update { it.copy(phase = BackupOperationPhase.STAGED) }

                entries = publishAndVerifyArchives(store, entries, newPassword, journalData)
                journalData = journalData.copy(phase = BackupOperationPhase.VERIFIED, entries = entries)
                journal.write(journalData)
                _operationState.update { it.copy(phase = BackupOperationPhase.VERIFIED) }

                backupSettings.updateBackupPasswordHash(newHash)
                journalData = journalData.copy(phase = BackupOperationPhase.CREDENTIAL_COMMITTED)
                journal.write(journalData)
                _operationState.update { it.copy(phase = BackupOperationPhase.CREDENTIAL_COMMITTED) }

                _operationState.update { it.copy(phase = BackupOperationPhase.CLEANUP) }
                executeCleanup(store, entries)
                stagingDir.deleteRecursively()
                journal.delete()

                _operationState.value =
                    BackupOperationState(
                        operationId = operationId,
                        phase = BackupOperationPhase.COMPLETE,
                        completedArchives = entries.size,
                        totalArchives = entries.size,
                    )
                recordAuditEvent(AuditEvent.Type.KEY_ROTATED, null)
                Result.success(Unit)
            } catch (e: CancellationException) {
                handleFailure(store, journalData, stagingDir, e)
                throw e
            } catch (e: Throwable) {
                logE("BackupRotationService", e) { "Password rotation failed" }
                handleFailure(store, journalData, stagingDir, e)
                Result.failure(e)
            }
        }

        private suspend fun stageArchives(
            store: BackupStore,
            entries: List<ArchiveRotationEntry>,
            stagingDir: File,
            oldPassword: String?,
            newPassword: String,
        ): List<ArchiveRotationEntry> =
            entries.mapIndexed { index, entry ->
                coroutineContext.ensureActive()
                val stagedZip = File(stagingDir, "staged_${index}_${System.currentTimeMillis()}.zip")
                val tempSource = File(stagingDir, "temp_source_$index.zip")
                try {
                    store.read(BackupLocation(entry.originalLocation)).use { input ->
                        tempSource.outputStream().use { output -> input.copyTo(output) }
                    }
                    val oldPasswordChars = oldPassword?.takeIf { it.isNotEmpty() }?.toCharArray()
                    ZipFile(tempSource, oldPasswordChars).use { sourceZip ->
                        streamZipEntries(sourceZip, stagedZip, newPassword.takeIf { it.isNotBlank() })
                    }
                    entry.copy(stagedLocation = stagedZip.absolutePath)
                } finally {
                    tempSource.delete()
                }
            }

        private suspend fun publishAndVerifyArchives(
            store: BackupStore,
            entries: List<ArchiveRotationEntry>,
            newPassword: String,
            journalData: RotationJournalData,
        ): List<ArchiveRotationEntry> {
            _operationState.update { it.copy(phase = BackupOperationPhase.PUBLISHING) }
            val published = mutableListOf<ArchiveRotationEntry>()
            entries.forEachIndexed { index, entry ->
                coroutineContext.ensureActive()
                val stagedFile = File(entry.stagedLocation ?: error("Missing staged location"))
                val targetName = generateTargetName(entry.originalLocation, index)
                val location =
                    verifiedPublisher.publishAndVerify(
                        store,
                        stagedFile,
                        targetName,
                        newPassword.takeIf { it.isNotBlank() },
                    )
                val updatedEntry = entry.copy(publishedLocation = location.value, verified = true)
                published.add(updatedEntry)
                val remaining = entries.drop(index + 1)
                journal.write(
                    journalData.copy(
                        phase = BackupOperationPhase.PUBLISHING,
                        entries = published + remaining,
                    ),
                )
                _operationState.update { it.copy(completedArchives = published.size) }
            }
            return published
        }

        private suspend fun handleFailure(
            store: BackupStore,
            data: RotationJournalData,
            stagingDir: File,
            e: Throwable,
        ) {
            withContext(NonCancellable) {
                runCatching {
                    if (data.phase < BackupOperationPhase.CREDENTIAL_COMMITTED) {
                        executeRollback(store, data.entries)
                        stagingDir.deleteRecursively()
                        journal.delete()
                    }
                    recordAuditEvent(AuditEvent.Type.KEY_ROTATION_FAILED, e::class.simpleName)
                }
            }
        }

        private suspend fun recoverPreviousJournalIfAny(store: BackupStore) {
            val previous = journal.read() ?: return
            if (previous.phase == BackupOperationPhase.CREDENTIAL_COMMITTED) {
                executeCleanup(store, previous.entries)
            } else {
                executeRollback(store, previous.entries)
            }
            journal.delete()
        }

        private suspend fun executeCleanup(
            store: BackupStore,
            entries: List<ArchiveRotationEntry>,
        ) {
            entries.forEach { entry ->
                runCatching { store.delete(BackupLocation(entry.originalLocation)) }
                entry.stagedLocation?.let { runCatching { File(it).delete() } }
            }
        }

        private suspend fun executeRollback(
            store: BackupStore,
            entries: List<ArchiveRotationEntry>,
        ) {
            entries.forEach { entry ->
                entry.publishedLocation?.let { runCatching { store.delete(BackupLocation(it)) } }
                entry.stagedLocation?.let { runCatching { File(it).delete() } }
            }
        }

        private suspend fun recordAuditEvent(
            type: AuditEvent.Type,
            detail: String?,
        ) {
            runCatching {
                auditTrailRepository.appendBestEffort(
                    "BackupRotationService",
                    AuditEvent(
                        type = type,
                        occurredAt = Instant.now(),
                        detail = detail,
                    ),
                )
            }
        }
    }

private fun generateTargetName(
    originalLocation: String,
    index: Int,
): String {
    val decoded = Uri.decode(originalLocation)
    val segment = decoded.substringAfterLast('/').substringAfterLast(':')
    val baseName = segment.removeSuffix(".zip").ifBlank { "backup" }
    val safeBaseName = if (baseName.startsWith("backup")) baseName else "backup_$baseName"
    return "${safeBaseName}_r${System.currentTimeMillis()}_$index.zip"
}

private fun createZipEntryParameters(
    fileName: String,
    newPassword: String?,
): ZipParameters {
    val params = ZipParameters()
    params.fileNameInZip = fileName
    if (newPassword != null) {
        params.isEncryptFiles = true
        params.encryptionMethod = EncryptionMethod.AES
        params.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
    }
    return params
}

private fun streamZipEntries(
    source: ZipFile,
    newZipPath: File,
    newPassword: String?,
) {
    net.lingala.zip4j.io.outputstream
        .ZipOutputStream(
            newZipPath.outputStream().buffered(),
            newPassword?.toCharArray(),
        ).use { sink ->
            for (header in source.fileHeaders) {
                sink.putNextEntry(createZipEntryParameters(header.fileName, newPassword))
                source.getInputStream(header).use { input -> input.copyTo(sink) }
                sink.closeEntry()
            }
        }
}
