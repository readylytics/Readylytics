package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalRestoreManagerTest {
    private lateinit var context: Context
    private lateinit var db: HealthDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var manager: LocalRestoreManager

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
        manager = LocalRestoreManager(context, db, settingsRepo, encryptionManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createBackupZipFile(
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

    @Test
    fun validate_correctJson_returnsManifest() =
        runTest {
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("valid_backup.zip", json)

            val result = manager.validate(Uri.fromFile(zipFile))

            assertTrue(result.isSuccess)
            val manifest = result.getOrNull()
            assertEquals(HealthDatabase.DATABASE_VERSION, manifest?.schemaVersion)
            assertEquals(1, manifest?.rowCounts?.get("sleepSessions"))
            zipFile.delete()
        }

    @Test
    fun validate_wrongVersion_returnsFailure() =
        runTest {
            val json = createValidBackupJson()
            json.put("schemaVersion", 1)
            val zipFile = createBackupZipFile("old_backup.zip", json)

            val result = manager.validate(Uri.fromFile(zipFile))

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("schema version") == true)
            zipFile.delete()
        }

    @Test
    fun applyRestore_insertsDataIntoDb() =
        runTest {
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("restore_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is LocalRestoreManager.RestoreResult.SuccessRequiresRestart)

            val sessions = db.sleepSessionDao().getSince(0)
            assertEquals(1, sessions.size)
            assertEquals("session_1", sessions[0].id)
            zipFile.delete()
        }

    @Test
    fun applyRestore_updatesPreferences() =
        runTest {
            val json = createValidBackupJson()
            json.getJSONObject("preferences").put("goalSleepHours", 9.5)
            val zipFile = createBackupZipFile("prefs_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is LocalRestoreManager.RestoreResult.SuccessRequiresRestart)
            coVerify { settingsRepo.batchUpdate(any()) }
            zipFile.delete()
        }

    private fun createValidBackupJson(): JSONObject {
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
}
