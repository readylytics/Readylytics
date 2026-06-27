package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.BackupScheduleProto
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferencesProto
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.backup.RestoreStage
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.workers.WorkerScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalRestoreManagerTest {
    private lateinit var context: Context
    private lateinit var db: HealthDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var cardConfigRepo: CardConfigurationRepository
    private lateinit var workerScheduler: WorkerScheduler
    private lateinit var auditTrailRepository: FakeAuditTrailRepository
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
        every { encryptionManager.encrypt("restored_password") } returns "encrypted_restored_password"
        cardConfigRepo = mockk<CardConfigurationRepository>(relaxed = true)
        workerScheduler = mockk<WorkerScheduler>(relaxed = true)
        auditTrailRepository = FakeAuditTrailRepository()
        manager =
            LocalRestoreManager(
                context,
                db,
                settingsRepo,
                cardConfigRepo,
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
            json.put("schemaVersion", HealthDatabase.DATABASE_VERSION + 1)
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

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

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

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify { settingsRepo.batchUpdate(any()) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresHeight() =
        runTest {
            val json = createValidBackupJson()
            json.getJSONObject("preferences").put("heightCm", 182.5)
            val zipFile = createBackupZipFile("height_backup.zip", json)

            val builderSlot =
                io.mockk
                    .slot<app.readylytics.health.data.preferences.UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder =
                app.readylytics.health.data.preferences.UserPreferencesProto
                    .newBuilder()
            builderSlot.captured(builder)
            assertEquals(182.5f, builder.heightCm)
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresDashboardCards() =
        runTest {
            val json = createValidBackupJson()
            val cardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "READINESS")
                            put("isVisible", true)
                            put("position", 2)
                        },
                    )
                }
            json.getJSONObject("preferences").put("dashboardCards", cardsJson)
            val zipFile = createBackupZipFile("cards_backup.zip", json)

            coEvery { cardConfigRepo.updateDashboardCardConfigurations(any()) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val expectedCards =
                listOf(
                    CardConfiguration(
                        cardId = app.readylytics.health.domain.dashboard.CardId.READINESS,
                        isVisible = true,
                        position = 2,
                    ),
                )
            coVerify(exactly = 1) {
                cardConfigRepo.updateDashboardCardConfigurations(expectedCards)
            }
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresBackgroundSyncAndSchedulesPeriodicSync() =
        runTest {
            val json = createValidBackupJson()
            json
                .getJSONObject("preferences")
                .put("backgroundSyncEnabled", true)
                .put("backgroundSyncIntervalMinutes", 180)
            val zipFile = createBackupZipFile("background_sync_backup.zip", json)

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit
            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder = UserPreferencesProto.newBuilder()
            builderSlot.captured(builder)
            assertTrue(builder.backgroundSyncEnabled)
            assertEquals(180, builder.backgroundSyncIntervalMinutes)
            coVerify(exactly = 1) { workerScheduler.schedulePeriodicSync(180L) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_disablesBackgroundSyncWhenBackupHadItDisabled() =
        runTest {
            val json = createValidBackupJson()
            json
                .getJSONObject("preferences")
                .put("backgroundSyncEnabled", false)
                .put("backgroundSyncIntervalMinutes", 360)
            val zipFile = createBackupZipFile("background_sync_disabled_backup.zip", json)

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder = UserPreferencesProto.newBuilder().setBackgroundSyncEnabled(true)
            builderSlot.captured(builder)
            assertTrue(!builder.backgroundSyncEnabled)
            verify(exactly = 1) { workerScheduler.cancelPeriodicSync() }
            zipFile.delete()
        }

    @Test
    fun applyRestore_storesProvidedPasswordForFutureBackups() =
        runTest {
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("provided_password_backup.zip", json)

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile), providedPassword = "restored_password")

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder = UserPreferencesProto.newBuilder()
            builderSlot.captured(builder)
            assertEquals("encrypted_restored_password", builder.backupPasswordHash)
        }

    @Test
    fun applyRestore_rollsBackDbChangesWhenDatabaseRestoreFails() =
        runTest {
            val json = createValidBackupJson()
            json.put(
                "sleepSessions",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", JSONObject())
                        },
                    )
                },
            )
            val zipFile = createBackupZipFile("rollback_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.Failure)
            assertTrue(result.cause is kotlinx.serialization.SerializationException)

            val sessions = db.sleepSessionDao().getSince(0)
            assertTrue(sessions.isEmpty())
            assertEquals(
                listOf(AuditEvent.Type.RESTORE_STARTED, AuditEvent.Type.RESTORE_FAILED),
                auditTrailRepository.events.map { it.type },
            )
            val detail = auditTrailRepository.events.last().detail
            assertTrue(detail != null && detail.contains("Exception"))
            zipFile.delete()
        }

    @Test
    fun applyRestore_returnsSuccessWhenStartedAuditAppendFails() =
        runTest {
            val zipFile = createBackupZipFile("started_audit_failure.zip", createValidBackupJson())
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.RESTORE_STARTED) RuntimeException("audit unavailable") else null
            }

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            zipFile.delete()
        }

    @Test
    fun applyRestore_returnsSuccessWhenCompletedAuditAppendFails() =
        runTest {
            val zipFile = createBackupZipFile("completed_audit_failure.zip", createValidBackupJson())
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.RESTORE_COMPLETED) RuntimeException("audit unavailable") else null
            }

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            zipFile.delete()
        }

    @Test
    fun applyRestore_preservesOriginalFailureWhenFailureAuditAppendFails() =
        runTest {
            val json = createValidBackupJson()
            json.put(
                "sleepSessions",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", JSONObject())
                        },
                    )
                },
            )
            val zipFile = createBackupZipFile("failure_audit_failure.zip", json)
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.RESTORE_FAILED) RuntimeException("audit unavailable") else null
            }

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.Failure)
            assertTrue(result.cause is kotlinx.serialization.SerializationException)
            zipFile.delete()
        }

    @Test
    fun applyRestore_rethrowsCancellationFromRestoreOperation() =
        runTest {
            val zipFile = createBackupZipFile("restore_cancellation.zip", createValidBackupJson())
            coEvery { settingsRepo.batchUpdate(any()) } throws CancellationException("cancel restore")

            assertFailsWith<CancellationException> {
                manager.applyRestore(Uri.fromFile(zipFile))
            }
            zipFile.delete()
        }

    @Test
    fun applyRestore_rethrowsCancellationFromAuditAppend() =
        runTest {
            val zipFile = createBackupZipFile("audit_cancellation.zip", createValidBackupJson())
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.RESTORE_STARTED) CancellationException("cancel audit") else null
            }

            assertFailsWith<CancellationException> {
                manager.applyRestore(Uri.fromFile(zipFile))
            }
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresBackupScheduleAndSchedulesBackupWorker() =
        runTest {
            val json = createValidBackupJson()
            json.getJSONObject("preferences").put("backupSchedule", "BACKUP_WEEKLY")
            val zipFile = createBackupZipFile("backup_schedule_backup.zip", json)

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder = UserPreferencesProto.newBuilder()
            builderSlot.captured(builder)
            assertEquals(BackupScheduleProto.BACKUP_WEEKLY, builder.backupSchedule)
            coVerify(exactly = 1) { workerScheduler.scheduleBackupWorker(BackupSchedule.WEEKLY) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_whenPreferencesFail_returnsPartialSuccessWithCommittedDatabase() =
        runTest {
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("restore_partial_fail.zip", json)

            coEvery { settingsRepo.batchUpdate(any()) } throws RuntimeException("prefs fail")

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.PartialSuccessRequiresRestart)
            assertEquals(
                RestoreStage.PREFERENCES,
                result.failedStage,
            )

            val sessions = db.sleepSessionDao().getSince(0)
            assertTrue(sessions.isNotEmpty())
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

    private class FakeAuditTrailRepository : AuditTrailRepository {
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
