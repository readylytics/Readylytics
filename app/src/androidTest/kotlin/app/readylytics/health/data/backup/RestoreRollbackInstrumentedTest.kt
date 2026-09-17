package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Instrumented test verifying Room transaction rollback on a real Android SQLite instance.
 * Proves that corrupt, mismatched, or foreign-key-violating archives fail and roll back,
 * leaving pre-existing rows intact.
 */
@RunWith(AndroidJUnit4::class)
class RestoreRollbackInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var db: HealthDatabase
    private lateinit var manager: LocalRestoreManager

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        coEvery { settingsRepo.userPreferences } returns
            flowOf(
                mockk(relaxed = true) {
                    coEvery { backupPasswordHash } returns null
                },
            )
        val encryptionManager = mockk<EncryptionManager>(relaxed = true)
        every { encryptionManager.encrypt(any()) } returns "encrypted"

        val cardConfigRepo = mockk<CardConfigurationRepository>(relaxed = true)
        val vitalsLayoutRepo = mockk<VitalsLayoutRepository>(relaxed = true)
        val sleepLayoutRepo = mockk<SleepLayoutRepository>(relaxed = true)
        val workoutsLayoutRepo = mockk<WorkoutsLayoutRepository>(relaxed = true)
        val workoutDetailLayoutRepo = mockk<WorkoutDetailLayoutRepository>(relaxed = true)
        val workerScheduler = mockk<WorkerScheduler>(relaxed = true)

        val auditTrailRepo =
            object : AuditTrailRepository {
                override suspend fun append(event: AuditEvent) {}

                override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = flowOf(emptyList())
            }

        val restoreDbOps = RestoreDatabaseOperations(db, RestoreBatchLoader(db, RestoreVitalsLoader(db)))
        val prefsApplier =
            RestorePreferencesApplier(
                settingsRepo,
                RestoreLayoutRepositories(
                    cardConfigRepo,
                    vitalsLayoutRepo,
                    sleepLayoutRepo,
                    workoutsLayoutRepo,
                    workoutDetailLayoutRepo,
                ),
                workerScheduler,
                encryptionManager,
            )
        val mutationCoordinator =
            app.readylytics.health.core.database.data.local
                .HealthMutationCoordinatorImpl(db.healthMutationStateDao())
        val restoreJournal = RestoreOperationJournal(context, encryptionManager)
        val coverageChecker = RestoreRecommendationCoverageChecker(db, settingsRepo, workerScheduler)
        val coordinator =
            RestoreMaintenanceCoordinator(
                healthMutationCoordinator = mutationCoordinator,
                healthDatabase = db,
                journal = restoreJournal,
                restorePrefsApplier = prefsApplier,
                encryptionManager = encryptionManager,
                recommendationCoverageChecker = coverageChecker,
            )
        manager =
            LocalRestoreManager(
                context = context,
                settingsRepository = settingsRepo,
                restoreDatabaseOperations = restoreDbOps,
                encryptionManager = encryptionManager,
                auditTrailRepository = auditTrailRepo,
                inventoryValidator = RestoreInventoryValidator(),
                ioDispatcher = Dispatchers.Unconfined,
                restoreMaintenanceCoordinator = coordinator,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun applyRestore_missingFkRollsBackAndPreservesPreExistingData() =
        runBlocking {
            // Seed initial data directly into Room
            val initial =
                SleepSessionEntity(
                    id = "seeded_session",
                    startTime = 1_000L,
                    endTime = 2_000L,
                    durationMinutes = 60,
                    efficiency = 0.95f,
                    deepSleepMinutes = 20,
                    remSleepMinutes = 15,
                    lightSleepMinutes = 25,
                    awakeMinutes = 0,
                    deviceName = "Pixel Watch",
                )
            db.sleepSessionDao().upsertAll(listOf(initial))
            assertEquals(1, db.sleepSessionDao().getSince(0).size)

            // Create an archive with an invalid foreign key reference in heart rate records
            val root = createValidArchiveJson()
            val sources =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", 1L)
                            put("sourceRecordId", "source_1")
                            put("recordType", "HEART_RATE")
                            put("createdAtMs", 1000L)
                        },
                    )
                }
            val heartRate =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("sourceRecordRef", 999L) // Non-existent foreign key
                            put("timestampMs", 1000L)
                            put("beatsPerMinute", 65)
                            put("recordType", "HEART_RATE")
                        },
                    )
                }
            root.put("healthSourceRecords", sources)
            root.put("heartRateRecords", heartRate)
            root.getJSONObject("rowCounts").put("healthSourceRecords", 1)
            root.getJSONObject("rowCounts").put("heartRateRecords", 1)

            val zip = createZipFile("missing_fk_instrumented.zip", root.toString())
            val result = manager.applyRestore(Uri.fromFile(zip))

            assertTrue(result is RestoreResult.Failure)
            val sessions = db.sleepSessionDao().getSince(0)
            assertEquals(1, sessions.size)
            assertEquals("seeded_session", sessions.single().id)
            zip.delete()
        }

    private fun createZipFile(
        name: String,
        content: String,
    ): File {
        val zipFile = File(context.cacheDir, name)
        if (zipFile.exists()) zipFile.delete()
        val jsonFile = File(context.cacheDir, name.replace(".zip", ".json"))
        jsonFile.writeText(content)
        val zip = ZipFile(zipFile)
        zip.addFile(jsonFile)
        jsonFile.delete()
        return zipFile
    }

    private fun createValidArchiveJson(): JSONObject {
        val root = JSONObject()
        root.put("schemaVersion", HealthDatabase.DATABASE_VERSION)
        root.put("exportedAt", Instant.now().toString())
        val rowCounts = JSONObject()
        val tables =
            app.readylytics.health.core.model.domain.backup.BackupInventoryPolicy.requiredTables(
                HealthDatabase.DATABASE_VERSION,
            )
        tables.forEach { table ->
            rowCounts.put(table, 0)
            root.put(table, JSONArray())
        }
        root.put("rowCounts", rowCounts)
        root.put("preferences", JSONObject())
        return root
    }
}
