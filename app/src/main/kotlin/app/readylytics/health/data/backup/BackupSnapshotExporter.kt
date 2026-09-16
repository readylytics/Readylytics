package app.readylytics.health.data.backup

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.first
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupSnapshotExporter
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
        private val healthMutationCoordinator: HealthMutationCoordinator,
        private val settingsRepository: SettingsRepository,
        private val layoutRepositories: RestoreLayoutRepositories,
        private val backupStreamWriter: BackupStreamWriter,
    ) {
        suspend fun captureEncrypted(
            target: File,
            password: CharArray,
            pageHook: (suspend (tableName: String) -> Unit)? = null,
        ): BackupSnapshotIdentity {
            val operationId = "backup_export_${UUID.randomUUID()}"
            var snapshotIdentity: BackupSnapshotIdentity? = null

            healthMutationCoordinator.withMaintenance(operationId) {
                try {
                    val mutationState = healthDatabase.healthMutationStateDao().current()
                    val sourceGeneration = mutationState.sourceGeneration
                    val prefs = settingsRepository.userPreferences.first()
                    val layouts = captureLayouts()
                    val capturedPreferences = buildUserPreferencesBackup(prefs, layouts)

                    val hrMax =
                        app.readylytics.health.core.scoring.domain.util.HeartRateFormulas.resolveMaxHeartRate(
                            prefs,
                        )
                    val scoringSnapshotId = HistoricalRunIdentity.computeSnapshotId(prefs, hrMax)
                    val exportedAtEpochMs = System.currentTimeMillis()

                    val identity =
                        BackupSnapshotIdentity(
                            sourceGeneration = sourceGeneration,
                            scoringSnapshotId = scoringSnapshotId,
                            schemaVersion = HealthDatabase.DATABASE_VERSION,
                            exportedAtEpochMs = exportedAtEpochMs,
                        )

                    val parameters =
                        ZipParameters().apply {
                            fileNameInZip = "backup.json"
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }

                    ZipOutputStream(target.outputStream().buffered(), password).use { zip ->
                        zip.putNextEntry(parameters)
                        if (pageHook != null) {
                            backupStreamWriter.writeJsonStreaming(zip, capturedPreferences, identity, pageHook)
                        } else {
                            backupStreamWriter.writeJsonStreaming(zip, capturedPreferences, identity)
                        }
                        zip.closeEntry()
                    }

                    snapshotIdentity = identity
                } catch (e: Throwable) {
                    target.delete()
                    healthDatabase.healthMutationStateDao().setMaintenance(null, null)
                    throw e
                }
            }

            return snapshotIdentity
                ?: error("Backup snapshot export completed without producing an identity")
        }

        private suspend fun captureLayouts(): BackupLayoutSnapshots =
            BackupLayoutSnapshots(
                dashboardCards =
                    layoutRepositories.cardConfigurationRepository
                        .dashboardCardConfigurations()
                        .first(),
                vitalsCards = layoutRepositories.vitalsLayoutRepository.vitalsCardConfigurations().first(),
                vitalsCharts = layoutRepositories.vitalsLayoutRepository.vitalsChartConfigurations().first(),
                sleepTopCards = layoutRepositories.sleepLayoutRepository.sleepTopCardConfigurations().first(),
                sleepCharts = layoutRepositories.sleepLayoutRepository.sleepChartConfigurations().first(),
                sleepMetricCards = layoutRepositories.sleepLayoutRepository.sleepMetricCardConfigurations().first(),
                workoutCards = layoutRepositories.workoutsLayoutRepository.workoutCardConfigurations().first(),
                workoutCharts = layoutRepositories.workoutsLayoutRepository.workoutChartConfigurations().first(),
                workoutHistory = layoutRepositories.workoutsLayoutRepository.workoutHistoryConfigurations().first(),
                workoutDetailLayouts =
                    layoutRepositories.workoutDetailLayoutRepository
                        .allLayouts()
                        .first()
                        .mapKeys { it.key.name },
            )
    }
