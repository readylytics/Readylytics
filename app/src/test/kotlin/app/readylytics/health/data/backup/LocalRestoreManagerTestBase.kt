package app.readylytics.health.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        settingsRepo = mockk<SettingsRepository>(relaxed = true)
        coEvery { settingsRepo.userPreferences } returns
            flowOf(
                mockk(relaxed = true) {
                    coEvery { backupPasswordHash } returns null
                },
            )
        encryptionManager = mockk<EncryptionManager>(relaxed = true)
        every { encryptionManager.encrypt("restored_password") } returns "encrypted_restored_password"
        cardConfigRepo = mockk<CardConfigurationRepository>(relaxed = true)
        vitalsLayoutRepo = mockk<VitalsLayoutRepository>(relaxed = true)
        sleepLayoutRepo = mockk<SleepLayoutRepository>(relaxed = true)
        workoutsLayoutRepo = mockk<WorkoutsLayoutRepository>(relaxed = true)
        workoutDetailLayoutRepo = mockk<WorkoutDetailLayoutRepository>(relaxed = true)
        workerScheduler = mockk<WorkerScheduler>(relaxed = true)
        auditTrailRepository = FakeAuditTrailRepository()
        manager =
            LocalRestoreManager(
                context,
                db,
                settingsRepo,
                cardConfigRepo,
                vitalsLayoutRepo,
                sleepLayoutRepo,
                workoutsLayoutRepo,
                workoutDetailLayoutRepo,
                workerScheduler,
                encryptionManager,
                auditTrailRepository,
                Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    protected fun createBackupZipFile(
        fileName: String,
        json: JSONObject,
    ): File {
        val zipFile = File(context.cacheDir, fileName)
        if (zipFile.exists()) zipFile.delete()
        val jsonFile = File(context.cacheDir, fileName.replace(".zip", ".json"))
        jsonFile.writeText(json.toString())
        val zip = net.lingala.zip4j.ZipFile(zipFile)
        zip.addFile(jsonFile)
        jsonFile.delete()
        return zipFile
    }

    protected fun createValidBackupJson(): JSONObject {
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

        return JSONObject().apply {
            put("schemaVersion", HealthDatabase.DATABASE_VERSION)
            put("exportedAt", Instant.now().toString())
            put("rowCounts", JSONObject().apply { put("sleepSessions", 1) })
            put("sleepSessions", sleepSessions)
            put("heartRateRecords", JSONArray())
            put("hrvRecords", JSONArray())
            put("workouts", JSONArray())
            put("dailySummaries", JSONArray())
            put(
                "preferences",
                JSONObject().apply {
                    put("goalSleepHours", 8.0)
                },
            )
        }
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
