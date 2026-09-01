package app.readylytics.health.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SyncPreference
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for a production defect: `HealthSourceRecordEntity` and `HrMinuteBucketEntity`
 * shipped without `@Serializable`, so `LocalBackupManager.writeJsonStreaming`'s
 * `json.encodeToString(it)` fell back to a runtime serializer lookup and threw
 * `SerializationException: Serializer for class '…' is not found`.
 *
 * It escaped every existing test because the encode call sits inside `forEach` — an **empty table
 * never executes it**, and every other backup test builds a database with these two tables empty.
 * On a real device `SourceRecordDao.getOrCreateSourceRef` populates `health_source_records` on
 * every ingested Health Connect record, so backup failed for every user with any synced data.
 *
 * Therefore: these tests are only meaningful with **non-empty** tables. If a future refactor makes
 * the seeding a no-op, they silently stop guarding anything — hence the explicit row-count
 * assertions before the backup runs.
 */
@RunWith(RobolectricTestRunner::class)
class LocalBackupSerializationRegressionTest {
    private lateinit var context: Context
    private lateinit var db: HealthDatabase
    private lateinit var manager: LocalBackupManager
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        backupDir = File(context.filesDir, "backups")
        backupDir.deleteRecursively()

        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val encryptionManager =
            mockk<EncryptionManager>(relaxed = true).apply {
                every { decrypt(any()) } returns "test_password"
            }
        val backupStreamWriter =
            BackupStreamWriter(
                db,
                settingsRepo(),
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
                ),
            )
        manager =
            LocalBackupManager(
                context,
                settingsRepo(),
                backupStreamWriter,
                encryptionManager,
                RecordingAuditTrailRepository(),
                Dispatchers.Unconfined,
            )
    }

    private fun settingsRepo(): SettingsRepository =
        mockk<SettingsRepository>().apply {
            every { userPreferences } returns
                flowOf(
                    mockk(relaxed = true) {
                        coEvery { goalSleepHours } returns 8.0f
                        coEvery { syncPreference } returns SyncPreference.ALWAYS
                        coEvery { backgroundSyncEnabled } returns true
                        coEvery { backgroundSyncIntervalMinutes } returns 180
                        coEvery { hrrToleranceSeconds } returns 45
                        coEvery { appTheme } returns AppTheme.DARK
                        coEvery { backupSchedule } returns BackupSchedule.DAILY
                        coEvery { birthDate } returns "2000-01-01"
                        coEvery { backupDirectoryUri } returns null
                        coEvery { backupPasswordHash } returns "hashed_password"
                    },
                )
        }

    @After
    fun tearDown() {
        db.close()
        backupDir.deleteRecursively()
    }

    private suspend fun seedSourceRecords() {
        db.sourceRecordDao().insertAll(
            listOf(
                HealthSourceRecordEntity(sourceRecordId = "hc-source-1", recordType = "SLEEP", createdAtMs = 1_000L),
                HealthSourceRecordEntity(
                    sourceRecordId = "hc-source-2",
                    recordType = "HEART_RATE",
                    createdAtMs = 2_000L,
                ),
            ),
        )
        assertEquals(2, db.sourceRecordDao().getAll().size, "fixture must leave the table non-empty")
    }

    private suspend fun seedMinuteBuckets() {
        db.minuteBucketDao().upsertBuckets(
            listOf(
                HrMinuteBucketEntity(
                    bucketStartMs = 60_000L,
                    bucketEndMs = 120_000L,
                    minBpm = 52,
                    maxBpm = 61,
                    avgBpm = 56.5,
                    sampleCount = 12,
                    recordType = "SLEEP",
                    sessionId = "session-1",
                    deviceName = "Test Watch",
                ),
            ),
        )
        assertEquals(
            1,
            db.minuteBucketDao().pageAfter(Long.MIN_VALUE, "", "", "", 10).size,
            "fixture must leave the table non-empty",
        )
    }

    private fun readBackupJson(zip: File): JSONObject {
        val zipFile = ZipFile(zip, "test_password".toCharArray())
        val header = zipFile.fileHeaders.first()
        val text = zipFile.getInputStream(header).bufferedReader().readText()
        zipFile.close()
        return JSONObject(text)
    }

    /** Fails with SerializationException before the `@Serializable` fix. */
    @Test
    fun createBackup_withSourceRecordRows_succeedsAndRoundTripsThem() =
        runTest {
            seedSourceRecords()

            val result = manager.createBackup()

            assertTrue(result.isSuccess, "backup must not fail on a non-empty source-record table")
            val json = readBackupJson(result.getOrNull()!!)
            val records = json.getJSONArray("healthSourceRecords")
            assertEquals(2, records.length())
            assertEquals("hc-source-1", records.getJSONObject(0).getString("sourceRecordId"))
            assertEquals("HEART_RATE", records.getJSONObject(1).getString("recordType"))
        }

    /** The second landmine, one JSON section behind the first. */
    @Test
    fun createBackup_withMinuteBucketRows_succeedsAndRoundTripsThem() =
        runTest {
            seedMinuteBuckets()

            val result = manager.createBackup()

            assertTrue(result.isSuccess, "backup must not fail on a non-empty minute-bucket table")
            val json = readBackupJson(result.getOrNull()!!)
            val buckets = json.getJSONArray("hrMinuteBuckets")
            assertEquals(1, buckets.length())
            assertEquals(
                56.5,
                buckets.getJSONObject(0).getDouble("avgBpm"),
                0.0001,
                "avgBpm must survive the round trip",
            )
            assertEquals("session-1", buckets.getJSONObject(0).getString("sessionId"))
        }

    /** Both populated at once — the shape of a real device. */
    @Test
    fun createBackup_withEveryPreviouslyUnserializableTablePopulated_succeeds() =
        runTest {
            seedSourceRecords()
            seedMinuteBuckets()

            val result = manager.createBackup()

            assertTrue(result.isSuccess, "backup must succeed with every table populated")
            val json = readBackupJson(result.getOrNull()!!)
            assertEquals(2, json.getJSONArray("healthSourceRecords").length())
            assertEquals(1, json.getJSONArray("hrMinuteBuckets").length())
        }

    /** Local fake — LocalBackupManagerTest's equivalent is private to that class. */
    private class RecordingAuditTrailRepository : AuditTrailRepository {
        val events = mutableListOf<AuditEvent>()

        override suspend fun append(event: AuditEvent) {
            events += event
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEvent>> =
            flowOf(events.sortedByDescending { it.occurredAt }.take(limit))
    }
}
