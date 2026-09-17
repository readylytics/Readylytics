package app.readylytics.health.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
abstract class LocalRestoreManagerTestBase {
    protected lateinit var context: Context
    protected lateinit var db: HealthDatabase
    protected lateinit var settingsRepo: SettingsRepository
    protected lateinit var encryptionManager: EncryptionManager
    protected lateinit var cardConfigRepo: CardConfigurationRepository
    protected lateinit var vitalsLayoutRepo: VitalsLayoutRepository
    protected lateinit var sleepLayoutRepo: SleepLayoutRepository
    protected lateinit var workoutsLayoutRepo: WorkoutsLayoutRepository
    protected lateinit var workoutDetailLayoutRepo: WorkoutDetailLayoutRepository
    protected lateinit var workerScheduler: WorkerScheduler
    protected lateinit var auditTrailRepository: FakeAuditTrailRepository
    protected lateinit var manager: LocalRestoreManager
    protected lateinit var restoreMaintenanceCoordinator: RestoreMaintenanceCoordinator
    protected lateinit var restoreJournal: RestoreOperationJournal
    protected lateinit var mutationCoordinator: app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        setupMocks()
        val restoreDbOps =
            RestoreDatabaseOperations(db, RestoreBatchLoader(db, RestoreVitalsLoader(db), CoverageRestoreLoader(db)))
        restoreMaintenanceCoordinator = buildRestoreMaintenanceCoordinator()
        manager =
            LocalRestoreManager(
                context = context,
                settingsRepository = settingsRepo,
                restoreDatabaseOperations = restoreDbOps,
                encryptionManager = encryptionManager,
                auditTrailRepository = auditTrailRepository,
                inventoryValidator = RestoreInventoryValidator(),
                ioDispatcher = Dispatchers.Unconfined,
                restoreMaintenanceCoordinator = restoreMaintenanceCoordinator,
            )
    }

    private fun setupMocks() {
        settingsRepo = mockk<SettingsRepository>(relaxed = true)
        coEvery { settingsRepo.userPreferences } returns
            flowOf(
                mockk(relaxed = true) {
                    coEvery { backupPasswordHash } returns null
                },
            )
        encryptionManager = mockk<EncryptionManager>(relaxed = true)
        every { encryptionManager.encrypt(any()) } answers {
            val str = firstArg<String>()
            if (str == "restored_password") "encrypted_restored_password" else "enc_$str"
        }
        every { encryptionManager.decrypt(any()) } answers {
            val str = firstArg<String>()
            when {
                str == "encrypted_restored_password" -> "restored_password"
                str.startsWith("enc_") -> str.removePrefix("enc_")
                else -> str
            }
        }
        cardConfigRepo = mockk<CardConfigurationRepository>(relaxed = true)
        vitalsLayoutRepo = mockk<VitalsLayoutRepository>(relaxed = true)
        sleepLayoutRepo = mockk<SleepLayoutRepository>(relaxed = true)
        workoutsLayoutRepo = mockk<WorkoutsLayoutRepository>(relaxed = true)
        workoutDetailLayoutRepo = mockk<WorkoutDetailLayoutRepository>(relaxed = true)
        workerScheduler = mockk<WorkerScheduler>(relaxed = true)
        auditTrailRepository = FakeAuditTrailRepository()
    }

    private fun buildRestoreMaintenanceCoordinator(): RestoreMaintenanceCoordinator {
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
        mutationCoordinator =
            app.readylytics.health.core.database.data.local
                .HealthMutationCoordinatorImpl(db.healthMutationStateDao())
        restoreJournal = RestoreOperationJournal(context, encryptionManager)
        val coverageChecker = RestoreRecommendationCoverageChecker(db, settingsRepo, workerScheduler)
        return RestoreMaintenanceCoordinator(
            healthMutationCoordinator = mutationCoordinator,
            healthDatabase = db,
            journal = restoreJournal,
            restorePrefsApplier = prefsApplier,
            encryptionManager = encryptionManager,
            recommendationCoverageChecker = coverageChecker,
        )
    }

    @After
    fun tearDown() {
        restoreJournal.delete()
        db.close()
    }

    protected fun createBackupZipFile(
        fileName: String,
        json: JSONObject,
    ): File = createRawBackupZipFile(fileName, json.toString())

    protected fun createRawBackupZipFile(
        fileName: String,
        rawContent: String,
    ): File {
        val zipFile = File(context.cacheDir, fileName)
        if (zipFile.exists()) zipFile.delete()
        val jsonFile = File(context.cacheDir, fileName.replace(".zip", ".json"))
        jsonFile.writeText(rawContent)
        val zip = net.lingala.zip4j.ZipFile(zipFile)
        zip.addFile(jsonFile)
        jsonFile.delete()
        return zipFile
    }

    protected fun createValidBackupJson(): JSONObject = createValidBackupJsonForVersion(HealthDatabase.DATABASE_VERSION)

    protected fun createValidV5BackupJson(): JSONObject =
        createBaseBackupJson(5, setOf("sleepSessions", "heartRateRecords", "hrvRecords", "workouts", "dailySummaries"))

    protected fun createValidV7BackupJson(): JSONObject =
        createBaseBackupJson(7, setOf("sleepSessions", "heartRateRecords", "hrvRecords", "workouts", "dailySummaries"))

    protected fun createValidV9BeforeFixBackupJson(): JSONObject =
        createBaseBackupJson(9, setOf("sleepSessions", "heartRateRecords", "hrvRecords", "workouts", "dailySummaries"))

    protected fun createValidV9AfterFixBackupJson(): JSONObject {
        val tables =
            setOf(
                "sleepSessions",
                "heartRateRecords",
                "hrvRecords",
                "workouts",
                "dailySummaries",
                "weightRecords",
                "bodyFatRecords",
                "bloodPressureRecords",
                "oxygenSaturationRecords",
                "bodyTemperatureRecords",
                "stepRecords",
            )
        return createBaseBackupJson(9, tables)
    }

    protected fun createValidV10BackupJson(): JSONObject {
        val tables =
            setOf(
                "sleepSessions",
                "heartRateRecords",
                "hrvRecords",
                "workouts",
                "dailySummaries",
                "weightRecords",
                "bodyFatRecords",
                "bloodPressureRecords",
                "oxygenSaturationRecords",
                "bodyTemperatureRecords",
                "stepRecords",
                "healthSourceRecords",
                "hrMinuteBuckets",
            )
        return createBaseBackupJson(10, tables)
    }

    protected fun createValidBackupJsonForVersion(version: Int): JSONObject {
        val tables =
            app.readylytics.health.core.model.domain.backup.BackupInventoryPolicy
                .requiredTables(version)
        return createBaseBackupJson(version, tables)
    }

    private fun createBaseBackupJson(
        version: Int,
        tables: Set<String>,
    ): JSONObject {
        val sleepSessions =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("id", "session_1")
                        put("startTime", Instant.now().toEpochMilli())
                        put("endTime", Instant.now().plusSeconds(3600).toEpochMilli())
                        put("durationMinutes", 60)
                        put("efficiency", 0.9f)
                        put("deepSleepMinutes", 15)
                        put("remSleepMinutes", 10)
                        put("lightSleepMinutes", 35)
                        put("awakeMinutes", 0)
                        put("deviceName", "Test Device")
                    },
                )
            }

        val rowCountsJson = JSONObject()
        val root =
            JSONObject().apply {
                put("schemaVersion", version)
                put("exportedAt", Instant.now().toString())
                put(
                    "preferences",
                    JSONObject().apply {
                        put("goalSleepHours", 8.0)
                    },
                )
            }

        tables.forEach { table ->
            if (table == "sleepSessions") {
                rowCountsJson.put(table, 1)
                root.put(table, sleepSessions)
            } else {
                rowCountsJson.put(table, 0)
                root.put(table, JSONArray())
            }
        }
        root.put("rowCounts", rowCountsJson)
        return root
    }

    protected class FakeAuditTrailRepository : AuditTrailRepository {
        val events = mutableListOf<AuditEvent>()
        var appendFailure: (AuditEvent) -> Throwable? = { null }

        override suspend fun append(event: AuditEvent) {
            appendFailure(event)?.let { throw it }
            events += event
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEvent>> =
            flowOf(events.sortedByDescending { it.occurredAt }.take(limit))
    }
}
