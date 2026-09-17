package app.readylytics.health.data.backup

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.model.domain.backup.RestorePhase
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.backup.RestoreStage
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal class RestorePartialSuccessException(
    override val cause: Throwable,
) : RuntimeException("Restore partial success: preferences failed", cause)

@Singleton
class RestoreMaintenanceCoordinator
    @Inject
    constructor(
        private val healthMutationCoordinator: HealthMutationCoordinator,
        private val healthDatabase: HealthDatabase,
        private val journal: RestoreOperationJournal,
        private val restorePrefsApplier: RestorePreferencesApplier,
        private val encryptionManager: EncryptionManager,
        private val recommendationCoverageChecker: RestoreRecommendationCoverageChecker,
        private val tokenStore: HealthChangeTokenStore? = null,
        private val checkpointStore: ResyncCheckpointStore? = null,
    ) {
        private val restoreMutex = Mutex()
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun restore(
            archiveLocation: String,
            validatedManifest: BackupManifest,
            prefsBackup: UserPreferencesBackup?,
            providedPassword: String?,
            executeDatabaseReplacement: suspend (operationId: String) -> Unit,
        ): RestoreResult =
            restoreMutex.withLock {
                val operationId = "restore_${UUID.randomUUID()}"
                val prefsJson = prefsBackup?.let { json.encodeToString(it) }
                val encryptedPassword =
                    providedPassword?.takeIf { it.isNotBlank() }?.let { encryptionManager.encrypt(it) }

                try {
                    healthMutationCoordinator.withMaintenance(operationId) {
                        val journalData =
                            RestoreJournalData(
                                protocolVersion = 1,
                                operationId = operationId,
                                archiveLocation = archiveLocation,
                                restoredGeneration = validatedManifest.sourceGeneration,
                                phase = RestorePhase.VALIDATED,
                                preferencesJson = prefsJson,
                                encryptedPassword = encryptedPassword,
                            )
                        journal.write(journalData)

                        executeDatabaseReplacement(operationId)
                        val databaseCommittedData = journalData.copy(phase = RestorePhase.DATABASE_COMMITTED)
                        journal.write(databaseCommittedData)

                        if (prefsBackup != null) {
                            try {
                                restorePrefsApplier.restorePreferences(prefsBackup, providedPassword)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                logE("RestoreMaintenanceCoordinator", e) { "Preferences application failed" }
                                throw RestorePartialSuccessException(e)
                            }
                        }
                        val prefsCommittedData = databaseCommittedData.copy(phase = RestorePhase.PREFERENCES_COMMITTED)
                        journal.write(prefsCommittedData)

                        val cachesResetData = prefsCommittedData.copy(phase = RestorePhase.CACHES_RESET)
                        journal.write(cachesResetData)
                        tokenStore?.clearAll()
                        checkpointStore?.clear()

                        recommendationCoverageChecker.scheduleRecomputeIfIncomplete()
                        journal.write(cachesResetData.copy(phase = RestorePhase.COMPLETE))

                        journal.delete()
                        RestoreResult.SuccessRequiresRestart
                    }
                } catch (e: RestorePartialSuccessException) {
                    RestoreResult.PartialSuccessRequiresRestart(
                        failedStage = RestoreStage.PREFERENCES,
                        cause = e.cause,
                    )
                } catch (e: CancellationException) {
                    cleanupIfUncommitted(operationId)
                    throw e
                } catch (e: Throwable) {
                    logE("RestoreMaintenanceCoordinator", e) { "Restore failed" }
                    cleanupIfUncommitted(operationId)
                    RestoreResult.Failure(cause = e)
                }
            }

        private suspend fun cleanupIfUncommitted(operationId: String) {
            withContext(NonCancellable) {
                runCatching {
                    val dbState = healthDatabase.healthMutationStateDao().getOrCreate()
                    val currentJournal = journal.read()
                    if (dbState.maintenancePhase != "DATABASE_COMMITTED" &&
                        (currentJournal == null || currentJournal.phase == RestorePhase.VALIDATED)
                    ) {
                        journal.delete()
                        if (dbState.maintenanceOperationId == operationId) {
                            healthDatabase.healthMutationStateDao().setMaintenance(null, null)
                        }
                    }
                }
            }
        }

        suspend fun recoverInterruptedRestoreOnStartup(): Boolean =
            restoreMutex.withLock {
                val dbState = healthDatabase.healthMutationStateDao().getOrCreate()
                val journalData = journal.read()
                if (journalData == null) {
                    if (dbState.maintenanceOperationId != null && dbState.maintenancePhase != "DATABASE_COMMITTED") {
                        healthDatabase.healthMutationStateDao().setMaintenance(null, null)
                    }
                    return@withLock false
                }

                if (dbState.maintenancePhase == "DATABASE_COMMITTED" ||
                    journalData.phase >= RestorePhase.DATABASE_COMMITTED
                ) {
                    logI("RestoreMaintenanceCoordinator") {
                        "Recovering interrupted restore: op=${journalData.operationId} phase=${journalData.phase}"
                    }
                    try {
                        healthMutationCoordinator.withMaintenance(journalData.operationId) {
                            val prefs =
                                journalData.preferencesJson?.let {
                                    runCatching { json.decodeFromString<UserPreferencesBackup>(it) }.getOrNull()
                                }
                            val password =
                                journalData.encryptedPassword?.let {
                                    encryptionManager.decrypt(it)
                                }

                            if (journalData.phase < RestorePhase.PREFERENCES_COMMITTED && prefs != null) {
                                restorePrefsApplier.restorePreferences(prefs, password)
                                journal.write(journalData.copy(phase = RestorePhase.PREFERENCES_COMMITTED))
                            }

                            val cachesResetData = journalData.copy(phase = RestorePhase.CACHES_RESET)
                            journal.write(cachesResetData)
                            tokenStore?.clearAll()
                            checkpointStore?.clear()
                            recommendationCoverageChecker.scheduleRecomputeIfIncomplete()
                            journal.write(cachesResetData.copy(phase = RestorePhase.COMPLETE))
                            journal.delete()
                        }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logE("RestoreMaintenanceCoordinator", e) { "Failed to recover restore on startup" }
                        false
                    }
                } else {
                    journal.delete()
                    if (dbState.maintenanceOperationId == journalData.operationId) {
                        healthDatabase.healthMutationStateDao().setMaintenance(null, null)
                    }
                    false
                }
            }

        suspend fun isMaintenancePending(): Boolean =
            healthDatabase.healthMutationStateDao().get()?.maintenanceOperationId != null
    }
