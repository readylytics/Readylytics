package app.readylytics.health.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthMutationCoordinatorImpl
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SyncPreference
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.data.preferences.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class BackupSnapshotConsistencyTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var db: HealthDatabase
    private lateinit var coordinator: HealthMutationCoordinator
    private lateinit var preferencesFlow: MutableStateFlow<UserPreferences>
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var layoutRepositories: RestoreLayoutRepositories
    private lateinit var backupStreamWriter: BackupStreamWriter
    private lateinit var exporter: BackupSnapshotExporter
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        stagingDir =
            File(context.cacheDir, "test-backup-staging").apply {
                deleteRecursively()
                mkdirs()
            }

        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        runBlocking {
            db.healthMutationStateDao().upsert(
                HealthMutationStateEntity(
                    id = 1,
                    sourceGeneration = 10L,
                    backfillAfterSourceRef = 0L,
                ),
            )
        }

        coordinator = HealthMutationCoordinatorImpl(db.healthMutationStateDao())

        preferencesFlow =
            MutableStateFlow(
                UserPreferences(
                    goalSleepHours = 8.0f,
                    syncPreference = SyncPreference.ALWAYS,
                    backgroundSyncEnabled = true,
                    backgroundSyncIntervalMinutes = 180,
                    hrrToleranceSeconds = 45,
                    appTheme = AppTheme.DARK,
                    backupSchedule = BackupSchedule.DAILY,
                    birthDate = "1990-01-01",
                    age = 34,
                ),
            )

        settingsRepository =
            mockk<SettingsRepository>().apply {
                every { userPreferences } returns preferencesFlow
            }

        layoutRepositories = mockLayoutRepositories()
        backupStreamWriter = BackupStreamWriter(db)
        exporter =
            BackupSnapshotExporter(
                db,
                coordinator,
                settingsRepository,
                layoutRepositories,
                backupStreamWriter,
            )
    }

    private fun mockLayoutRepositories(): RestoreLayoutRepositories =
        RestoreLayoutRepositories(
            mockk<CardConfigurationRepository>(relaxed = true).apply {
                every { dashboardCardConfigurations() } returns flowOf(emptyList())
            },
            mockk<VitalsLayoutRepository>(relaxed = true).apply {
                every { vitalsCardConfigurations() } returns flowOf(emptyList())
                every { vitalsChartConfigurations() } returns flowOf(emptyList())
            },
            mockk<SleepLayoutRepository>(relaxed = true).apply {
                every { sleepTopCardConfigurations() } returns flowOf(emptyList())
                every { sleepChartConfigurations() } returns flowOf(emptyList())
                every { sleepMetricCardConfigurations() } returns flowOf(emptyList())
            },
            mockk<WorkoutsLayoutRepository>(relaxed = true).apply {
                every { workoutCardConfigurations() } returns flowOf(emptyList())
                every { workoutChartConfigurations() } returns flowOf(emptyList())
                every { workoutHistoryConfigurations() } returns flowOf(emptyList())
            },
            mockk<WorkoutDetailLayoutRepository>(relaxed = true).apply {
                every { allLayouts() } returns flowOf(emptyMap())
            },
        )

    @After
    fun tearDown() {
        db.close()
        stagingDir.deleteRecursively()
    }

    private suspend fun seedGenerationAData() {
        seedGenAHeartRateData()
        seedGenASleepAndSummaryData()
    }

    private suspend fun seedGenAHeartRateData() {
        db.sourceRecordDao().insertAll(
            listOf(
                HealthSourceRecordEntity(
                    id = 1L,
                    sourceRecordId = "gen-a-source-1",
                    recordType = "HEART_RATE",
                    createdAtMs = 100_000L,
                ),
                HealthSourceRecordEntity(
                    id = 2L,
                    sourceRecordId = "gen-a-source-2",
                    recordType = "SLEEP",
                    createdAtMs = 100_000L,
                ),
            ),
        )

        db.heartRateDao().upsertAll(
            listOf(
                HeartRateRecordEntity(
                    timestampMs = 100_000L,
                    beatsPerMinute = 65,
                    sourceRecordRef = 1L,
                    recordType = "RESTING",
                ),
            ),
        )

        db.minuteBucketDao().upsertBuckets(
            listOf(
                HrMinuteBucketEntity(
                    bucketStartMs = 100_000L,
                    bucketEndMs = 160_000L,
                    minBpm = 60,
                    maxBpm = 70,
                    avgBpm = 65.0,
                    sampleCount = 10,
                    recordType = "RESTING",
                    sessionId = "session-a",
                    deviceName = "Watch A",
                ),
            ),
        )
    }

    private suspend fun seedGenASleepAndSummaryData() {
        db.sleepSessionDao().upsertAll(
            listOf(
                SleepSessionEntity(
                    id = "session-sleep-a",
                    startTime = 50_000L,
                    endTime = 80_000L,
                    durationMinutes = 500,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 300,
                    awakeMinutes = 50,
                ),
            ),
        )

        db.dailySummaryDao().upsert(
            DailySummaryEntity(
                dateMidnightMs = 86_400_000L,
            ),
        )
    }

    private fun readBackupJson(
        zip: File,
        password: String,
    ): JSONObject {
        val zipFile = ZipFile(zip, password.toCharArray())
        val header = zipFile.fileHeaders.first { it.fileName.endsWith(".json") }
        val text = zipFile.getInputStream(header).bufferedReader().readText()
        zipFile.close()
        return JSONObject(text)
    }

    @Test
    fun exportHoldsMaintenanceGateAndExcludesCompetingMutationsUntilCaptureSettles() =
        runBlocking(Dispatchers.Default) {
            seedGenerationAData()

            val reachedPage = CompletableDeferred<Unit>()
            val releasePage = CompletableDeferred<Unit>()
            val targetZip = File(stagingDir, "test_snapshot.zip")
            val password = "password123".toCharArray()

            // 1. Launch snapshot export that pauses after streaming healthSourceRecords
            val exportDeferred =
                async {
                    exporter.captureEncrypted(targetZip, password) { table: String ->
                        if (table == "healthSourceRecords") {
                            reachedPage.complete(Unit)
                            releasePage.await()
                        }
                    }
                }

            reachedPage.await()

            // 2. While paused, competing mutation tries to advance generation and write generation B
            val competingMutationFinished = CompletableDeferred<Boolean>()
            val competingJob =
                launch {
                    coordinator.withMutation {
                        db.healthMutationStateDao().incrementGeneration() // Advances to 11
                        db.sourceRecordDao().insertAll(
                            listOf(
                                HealthSourceRecordEntity(
                                    id = 3L,
                                    sourceRecordId = "gen-b-source-3",
                                    recordType = "HEART_RATE",
                                    createdAtMs = 200_000L,
                                ),
                            ),
                        )
                        preferencesFlow.value = preferencesFlow.value.copy(goalSleepHours = 9.5f)
                    }
                    competingMutationFinished.complete(true)
                }

            // Competing mutation MUST be blocked while export holds the maintenance coordinator
            assertFalse(
                "Competing mutation must not complete while maintenance gate is held by export",
                competingMutationFinished.isCompleted,
            )

            // 3. Resume export
            releasePage.complete(Unit)
            val identity = exportDeferred.await()
            competingJob.join()

            // 4. Verification: The exported archive must reflect generation A only
            assertEquals(10L, identity.sourceGeneration)
            assertTrue(targetZip.exists())

            val json = readBackupJson(targetZip, "password123")
            assertEquals(10L, json.getLong("sourceGeneration"))

            val sources = json.getJSONArray("healthSourceRecords")
            assertEquals(2, sources.length())
            assertEquals("gen-a-source-1", sources.getJSONObject(0).getString("sourceRecordId"))
            assertEquals("gen-a-source-2", sources.getJSONObject(1).getString("sourceRecordId"))

            val prefsJson = json.getJSONObject("preferences")
            assertEquals(8.0, prefsJson.getDouble("goalSleepHours"), 0.001)

            // After export, competing mutation has applied generation B
            assertEquals(11L, db.healthMutationStateDao().current().sourceGeneration)
            assertEquals(3, db.sourceRecordDao().count())
        }

    @Test
    fun exportFailureCleansUpIncompleteStagingAndResetsMaintenanceState() =
        runBlocking(Dispatchers.Default) {
            seedGenerationAData()

            val targetZip = File(stagingDir, "failing_snapshot.zip")
            val password = "password123".toCharArray()

            var caughtException: Throwable? = null
            try {
                exporter.captureEncrypted(targetZip, password) { table: String ->
                    if (table == "healthSourceRecords") {
                        throw IOException("Simulated disk full or I/O failure during capture")
                    }
                }
            } catch (e: Throwable) {
                caughtException = e
            }

            assertTrue("Should have caught simulated failure", caughtException is IOException)
            assertFalse("Incomplete staging file must be deleted on failure", targetZip.exists())

            // Maintenance state must be reset so subsequent mutations are not blocked
            val currentState = db.healthMutationStateDao().current()
            assertNull("Maintenance operation ID must be cleared after failure", currentState.maintenanceOperationId)

            // Subsequent mutation must succeed
            val nextMutationResult =
                coordinator.withMutation {
                    db.healthMutationStateDao().incrementGeneration()
                    db.healthMutationStateDao().current().sourceGeneration
                }
            assertEquals(11L, nextMutationResult)
        }

    @Test
    fun exportCancellationCleansUpIncompleteStagingAndResetsMaintenanceState() =
        runBlocking(Dispatchers.Default) {
            seedGenerationAData()

            val targetZip = File(stagingDir, "cancelled_snapshot.zip")
            val password = "password123".toCharArray()

            var caughtException: Throwable? = null
            try {
                exporter.captureEncrypted(targetZip, password) { table: String ->
                    if (table == "healthSourceRecords") {
                        throw CancellationException("Simulated coroutine cancellation mid-stream")
                    }
                }
            } catch (e: Throwable) {
                caughtException = e
            }

            assertTrue("Should have caught CancellationException", caughtException is CancellationException)
            assertFalse("Incomplete staging file must be deleted on cancellation", targetZip.exists())

            // Maintenance state must be reset even on cancellation
            val currentState = db.healthMutationStateDao().current()
            assertNull(
                "Maintenance operation ID must be cleared after cancellation",
                currentState.maintenanceOperationId,
            )

            // Subsequent mutation must succeed
            val nextMutationResult =
                coordinator.withMutation {
                    db.healthMutationStateDao().incrementGeneration()
                    db.healthMutationStateDao().current().sourceGeneration
                }
            assertEquals(11L, nextMutationResult)
        }
}
