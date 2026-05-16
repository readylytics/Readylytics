package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalRestoreManagerTest {
    private lateinit var context: Context
    private lateinit var db: HealthDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var manager: LocalRestoreManager
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        backupDir = File(context.filesDir, "backups")
        backupDir.deleteRecursively()

        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .build()

        settingsRepo =
            mockk<SettingsRepository>().apply {
                coEvery { userPreferences } returns flowOf(mockk(relaxed = true))
                coEvery { updateGoalSleepHours(any()) } returns Unit
                coEvery { updateBirthday(any(), any(), any()) } returns Unit
            }

        manager = LocalRestoreManager(context, db, settingsRepo)
    }

    @After
    fun tearDown() {
        db.close()
        backupDir.deleteRecursively()
    }

    @Test
    fun validate_validBackup_returnsManifest() =
        runTest {
            val json =
                JSONObject()
                    .put("schemaVersion", 18)
                    .put("exportedAt", Instant.now().toString())
                    .put("rowCounts", JSONObject(mapOf("sleepSessions" to 1)))
                    .put("sleepSessions", JSONArray(listOf(JSONObject())))
                    .put("heartRateRecords", JSONArray())
                    .put("hrvRecords", JSONArray())
                    .put("workouts", JSONArray())
                    .put("dailySummaries", JSONArray())
                    .put("preferences", JSONObject())

            backupDir.mkdirs()
            val backupFile = File(backupDir, "backup.json")
            backupFile.writeText(json.toString())

            val result = manager.validate(backupFile)
            assertTrue(result.isSuccess)
            assertEquals(18, result.getOrNull()?.schemaVersion)
        }

    @Test
    fun validate_schemaVersionMismatch_returnsFailure() =
        runTest {
            val json =
                JSONObject()
                    .put("schemaVersion", 99)
                    .put("exportedAt", Instant.now().toString())
                    .put("rowCounts", JSONObject())
                    .put("sleepSessions", JSONArray())
                    .put("heartRateRecords", JSONArray())
                    .put("hrvRecords", JSONArray())
                    .put("workouts", JSONArray())
                    .put("dailySummaries", JSONArray())
                    .put("preferences", JSONObject())

            backupDir.mkdirs()
            val backupFile = File(backupDir, "backup.json")
            backupFile.writeText(json.toString())

            val result = manager.validate(backupFile)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("schema version") == true)
        }

    @Test
    fun validate_rowCountMismatch_returnsFailure() =
        runTest {
            val json =
                JSONObject()
                    .put("schemaVersion", 18)
                    .put("exportedAt", Instant.now().toString())
                    .put("rowCounts", JSONObject(mapOf("sleepSessions" to 5)))
                    .put("sleepSessions", JSONArray(listOf(JSONObject())))
                    .put("heartRateRecords", JSONArray())
                    .put("hrvRecords", JSONArray())
                    .put("workouts", JSONArray())
                    .put("dailySummaries", JSONArray())
                    .put("preferences", JSONObject())

            backupDir.mkdirs()
            val backupFile = File(backupDir, "backup.json")
            backupFile.writeText(json.toString())

            val result = manager.validate(backupFile)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("Row count mismatch") == true)
        }

    @Test
    fun applyRestore_insertsAllRows() =
        runTest {
            val sessionJson =
                JSONObject()
                    .put("id", "session_1")
                    .put("startTime", 1000L)
                    .put("endTime", 2000L)
                    .put("durationMinutes", 60)
                    .put("efficiency", 0.9f)
                    .put("deepSleepMinutes", 20)
                    .put("lightSleepMinutes", 30)
                    .put("remSleepMinutes", 10)
                    .put("awakeMinutes", 0)
                    .put("deviceName", "Device")

            val json =
                JSONObject()
                    .put("schemaVersion", 18)
                    .put("exportedAt", Instant.now().toString())
                    .put("rowCounts", JSONObject(mapOf("sleepSessions" to 1)))
                    .put("sleepSessions", JSONArray(listOf(sessionJson)))
                    .put("heartRateRecords", JSONArray())
                    .put("hrvRecords", JSONArray())
                    .put("workouts", JSONArray())
                    .put("dailySummaries", JSONArray())
                    .put("preferences", JSONObject())

            backupDir.mkdirs()
            val backupFile = File(backupDir, "backup.json")
            backupFile.writeText(json.toString())

            val result = manager.applyRestore(backupFile)
            assertTrue(result is LocalRestoreManager.RestoreResult.Success)

            val sessions = db.sleepSessionDao().getSince(0)
            assertEquals(1, sessions.size)
        }

    @Test
    fun applyRestore_replacesExistingData() =
        runTest {
            db
                .sleepSessionDao()
                .upsertAll(
                    listOf(
                        SleepSessionEntity(
                            id = "old_session",
                            startTime = 0L,
                            endTime = 1L,
                            durationMinutes = 0,
                            efficiency = 0f,
                            deepSleepMinutes = 0,
                            lightSleepMinutes = 0,
                            remSleepMinutes = 0,
                            awakeMinutes = 0,
                            deviceName = "Old",
                        ),
                    ),
                )

            val sessionJson =
                JSONObject()
                    .put("id", "new_session")
                    .put("startTime", 1000L)
                    .put("endTime", 2000L)
                    .put("durationMinutes", 60)
                    .put("efficiency", 0.9f)
                    .put("deepSleepMinutes", 20)
                    .put("lightSleepMinutes", 30)
                    .put("remSleepMinutes", 10)
                    .put("awakeMinutes", 0)
                    .put("deviceName", "New")

            val json =
                JSONObject()
                    .put("schemaVersion", 18)
                    .put("exportedAt", Instant.now().toString())
                    .put("rowCounts", JSONObject(mapOf("sleepSessions" to 1)))
                    .put("sleepSessions", JSONArray(listOf(sessionJson)))
                    .put("heartRateRecords", JSONArray())
                    .put("hrvRecords", JSONArray())
                    .put("workouts", JSONArray())
                    .put("dailySummaries", JSONArray())
                    .put("preferences", JSONObject())

            backupDir.mkdirs()
            val backupFile = File(backupDir, "backup.json")
            backupFile.writeText(json.toString())

            val result = manager.applyRestore(backupFile)
            assertTrue(result is LocalRestoreManager.RestoreResult.Success)

            val sessions = db.sleepSessionDao().getSince(0)
            assertEquals(1, sessions.size)
            assertEquals("new_session", sessions[0].id)
        }

    @Test
    fun applyRestore_corruptJson_leavesDataUntouched() =
        runTest {
            db
                .sleepSessionDao()
                .upsertAll(
                    listOf(
                        SleepSessionEntity(
                            id = "existing",
                            startTime = 1000L,
                            endTime = 2000L,
                            durationMinutes = 60,
                            efficiency = 0.9f,
                            deepSleepMinutes = 20,
                            lightSleepMinutes = 30,
                            remSleepMinutes = 10,
                            awakeMinutes = 0,
                            deviceName = "Device",
                        ),
                    ),
                )

            backupDir.mkdirs()
            val backupFile = File(backupDir, "backup.json")
            backupFile.writeText("{invalid json")

            val result = manager.applyRestore(backupFile)
            assertTrue(result is LocalRestoreManager.RestoreResult.Failure)

            val sessions = db.sleepSessionDao().getSince(0)
            assertEquals(1, sessions.size)
            assertEquals("existing", sessions[0].id)
        }
}
