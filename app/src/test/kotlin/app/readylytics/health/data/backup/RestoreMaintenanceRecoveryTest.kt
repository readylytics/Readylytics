package app.readylytics.health.data.backup

import android.net.Uri
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.model.domain.backup.RestorePhase
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.backup.RestoreStage
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.workers.HealthResyncWorker
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RestoreMaintenanceRecoveryTest : LocalRestoreManagerTestBase() {
    private suspend fun seedInitialSession() {
        val seedZip = createBackupZipFile("seed.zip", createValidBackupJson())
        assertTrue(manager.applyRestore(Uri.fromFile(seedZip)) is RestoreResult.SuccessRequiresRestart)
        seedZip.delete()
        assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
    }

    private fun createValidBackupWithSession2(goalSleepHours: Double = 9.0): File {
        val json = createValidBackupJson()
        json.getJSONObject("preferences").put("goalSleepHours", goalSleepHours)
        val sessions =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("id", "session_2")
                        put("startTime", Instant.now().toEpochMilli())
                        put("endTime", Instant.now().plusSeconds(7200).toEpochMilli())
                        put("durationMinutes", 120)
                        put("efficiency", 0.95f)
                        put("deepSleepMinutes", 30)
                        put("remSleepMinutes", 20)
                        put("lightSleepMinutes", 70)
                        put("awakeMinutes", 0)
                        put("deviceName", "Watch 2")
                    },
                )
            }
        json.put("sleepSessions", sessions)
        json.getJSONObject("rowCounts").put("sleepSessions", 1)
        return createBackupZipFile("backup_session_2.zip", json)
    }

    @Test
    fun failureBeforeDatabaseCommit_clearsMaintenanceAndLeavesOriginalData() =
        runTest {
            seedInitialSession()
            val invalidPayload = createValidBackupJson().apply { remove("heartRateRecords") }
            val archive = createBackupZipFile("invalid-before-db.zip", invalidPayload)

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)

            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            val state = db.healthMutationStateDao().current()
            assertNull(state.maintenanceOperationId)
            assertNull(state.maintenancePhase)
            assertNull(restoreJournal.read())
            assertFalse(mutationCoordinator.isMaintenancePending())

            archive.delete()
        }

    @Test
    fun failureAfterDatabaseCommit_keepsMaintenanceActiveAndBlocksMutations() =
        runTest {
            seedInitialSession()
            val archive = createValidBackupWithSession2(goalSleepHours = 9.5)

            coEvery {
                settingsRepo.batchUpdate(any())
            } throws RuntimeException("DataStore write failure")

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.PartialSuccessRequiresRestart)
            val partial = result as RestoreResult.PartialSuccessRequiresRestart
            assertEquals(RestoreStage.PREFERENCES, partial.failedStage)

            assertEquals(listOf("session_2"), db.sleepSessionDao().getSince(0).map { it.id })

            val dbState = db.healthMutationStateDao().current()
            assertNotNull(dbState.maintenanceOperationId)
            assertEquals("DATABASE_COMMITTED", dbState.maintenancePhase)

            val journalData = restoreJournal.read()
            assertNotNull(journalData)
            assertEquals(RestorePhase.DATABASE_COMMITTED, journalData!!.phase)
            assertEquals(dbState.maintenanceOperationId, journalData.operationId)

            assertTrue(mutationCoordinator.isMaintenancePending())
            var blocked = false
            try {
                mutationCoordinator.withMutation {
                    // Should never execute
                }
            } catch (e: IllegalStateException) {
                if (e.message?.contains("MAINTENANCE_PENDING") == true) {
                    blocked = true
                }
            }
            assertTrue("Expected withMutation to be blocked by maintenance", blocked)

            coVerify(exactly = 0) {
                workerScheduler.scheduleResyncWorker(any(), any(), any())
            }

            archive.delete()
        }

    @Test
    fun startupRecovery_recoversInterruptedRestoreAndUnblocksMutations() =
        runTest {
            seedInitialSession()
            val archive = createValidBackupWithSession2(goalSleepHours = 9.5)

            val builderSlot =
                io.mockk
                    .slot<app.readylytics.health.data.preferences.UserPreferencesProto.Builder.() -> Unit>()
            coEvery {
                settingsRepo.batchUpdate(any())
            } throws RuntimeException("DataStore write failure")

            val restoreResult = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(restoreResult is RestoreResult.PartialSuccessRequiresRestart)
            assertTrue(mutationCoordinator.isMaintenancePending())

            // Unblock settingsRepo
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            // Simulate application startup recovery
            val recovered = restoreMaintenanceCoordinator.recoverInterruptedRestoreOnStartup()
            assertTrue(recovered)

            val builder =
                app.readylytics.health.data.preferences.UserPreferencesProto
                    .newBuilder()
            builderSlot.captured(builder)
            assertEquals(9.5f, builder.goalSleepHours, 0.001f)
            assertNull(restoreJournal.read())

            val dbState = db.healthMutationStateDao().current()
            assertNull(dbState.maintenanceOperationId)
            assertNull(dbState.maintenancePhase)

            assertFalse(mutationCoordinator.isMaintenancePending())
            val mutationValue = mutationCoordinator.withMutation { 42 }
            assertEquals(42, mutationValue)

            archive.delete()
        }

    @Test
    fun startupRecovery_clearsOrphanedMaintenanceIfInterruptedBeforeDatabaseCommit() =
        runTest {
            val opId = "restore_orphaned_before_db"
            db.healthMutationStateDao().upsert(
                HealthMutationStateEntity(
                    id = 1,
                    sourceGeneration = 1L,
                    maintenanceOperationId = opId,
                    maintenancePhase = "ACTIVE",
                    backfillAfterSourceRef = 0,
                ),
            )
            restoreJournal.write(
                RestoreJournalData(
                    protocolVersion = 1,
                    operationId = opId,
                    archiveLocation = "/cache/dummy.zip",
                    restoredGeneration = 2L,
                    phase = RestorePhase.VALIDATED,
                    preferencesJson = null,
                    encryptedPassword = null,
                ),
            )

            assertTrue(mutationCoordinator.isMaintenancePending())

            val recovered = restoreMaintenanceCoordinator.recoverInterruptedRestoreOnStartup()
            assertFalse(recovered)

            assertNull(restoreJournal.read())
            val dbState = db.healthMutationStateDao().current()
            assertNull(dbState.maintenanceOperationId)
            assertNull(dbState.maintenancePhase)
            assertFalse(mutationCoordinator.isMaintenancePending())
        }

    @Test
    fun healthResyncWorker_retriesWhenMaintenanceIsPending() =
        runTest {
            db.healthMutationStateDao().upsert(
                HealthMutationStateEntity(
                    id = 1,
                    sourceGeneration = 1L,
                    maintenanceOperationId = "restore_in_progress",
                    maintenancePhase = "DATABASE_COMMITTED",
                    backfillAfterSourceRef = 0,
                ),
            )

            assertTrue(mutationCoordinator.isMaintenancePending())

            val workerParams = mockk<WorkerParameters>(relaxed = true)
            every { workerParams.taskExecutor } returns mockk(relaxed = true)
            every { workerParams.inputData } returns androidx.work.Data.EMPTY

            val readinessGate = mockk<DatabaseReadinessInspector>()
            every { readinessGate.inspect() } returns DatabaseReadiness.Ready

            val worker =
                HealthResyncWorker(
                    appContext = context,
                    params = workerParams,
                    fullHistoricalResyncUseCase = mockk(relaxed = true),
                    foregroundSyncController = mockk(relaxed = true),
                    databaseReadinessGate = readinessGate,
                    settingsRepository = mockk(relaxed = true),
                    healthMutationCoordinator = Lazy { mutationCoordinator },
                )

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.retry(), result)
        }
}
