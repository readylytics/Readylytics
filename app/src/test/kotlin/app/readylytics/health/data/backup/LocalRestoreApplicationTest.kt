package app.readylytics.health.data.backup

import android.net.Uri
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.data.preferences.BackupScheduleProto
import app.readylytics.health.data.preferences.SleepScoreWeightProfileProto
import app.readylytics.health.data.preferences.UserPreferencesProto
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.backup.RestoreStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalRestoreApplicationTest : LocalRestoreManagerTestBase() {
    @Test
    fun applyRestore_unknownRequestedDisplayModeRestoresNullWithoutRejectingBackup() =
        runTest {
            val json = createValidBackupJson()
            val cardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "READINESS")
                            put("isVisible", true)
                            put("position", 2)
                            put("requestedDisplayMode", "TREND")
                        },
                    )
                }
            json.getJSONObject("preferences").put("dashboardCards", cardsJson)
            val zipFile = createBackupZipFile("cards_unknown_mode_backup.zip", json)

            coEvery { cardConfigRepo.updateDashboardCardConfigurations(any()) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val expectedCards =
                listOf(
                    CardConfiguration(
                        cardId = app.readylytics.health.core.model.domain.dashboard.CardId.READINESS,
                        isVisible = true,
                        position = 2,
                        requestedDisplayMode = null,
                    ),
                )
            coVerify(exactly = 1) {
                cardConfigRepo.updateDashboardCardConfigurations(expectedCards)
            }
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresKnownRequestedDisplayMode() =
        runTest {
            val json = createValidBackupJson()
            val cardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "HRV")
                            put("isVisible", true)
                            put("position", 3)
                            put("requestedDisplayMode", "BAR")
                        },
                    )
                }
            json.getJSONObject("preferences").put("dashboardCards", cardsJson)
            val zipFile = createBackupZipFile("cards_known_mode_backup.zip", json)

            coEvery { cardConfigRepo.updateDashboardCardConfigurations(any()) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val expectedCards =
                listOf(
                    CardConfiguration(
                        cardId = app.readylytics.health.core.model.domain.dashboard.CardId.HRV,
                        isVisible = true,
                        position = 3,
                        requestedDisplayMode = DashboardCardDisplayMode.BAR,
                    ),
                )
            coVerify(exactly = 1) {
                cardConfigRepo.updateDashboardCardConfigurations(expectedCards)
            }
            zipFile.delete()
        }

    @Test
    fun applyRestore_backupMissingRequestedDisplayModeFieldRestoresNull() =
        runTest {
            val json = createValidBackupJson()
            val cardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "STEPS")
                            put("isVisible", true)
                            put("position", 5)
                        },
                    )
                }
            json.getJSONObject("preferences").put("dashboardCards", cardsJson)
            val zipFile = createBackupZipFile("cards_missing_mode_backup.zip", json)

            coEvery { cardConfigRepo.updateDashboardCardConfigurations(any()) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val expectedCards =
                listOf(
                    CardConfiguration(
                        cardId = app.readylytics.health.core.model.domain.dashboard.CardId.STEPS,
                        isVisible = true,
                        position = 5,
                        requestedDisplayMode = null,
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
    fun applyRestore_restoresLastRecalcSleepScoreBaseline() =
        runTest {
            val json = createValidBackupJson()
            json
                .getJSONObject("preferences")
                .put("lastRecalcSleepScoreWeightProfile", "RECOVERY_FOCUSED")
                .put("lastRecalcGoalSleepHours", 9.5)
                .put("lastRecalcHypersomniaOnsetPercent", 115)
            val zipFile = createBackupZipFile("last_recalc_baseline_backup.zip", json)

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder = UserPreferencesProto.newBuilder()
            builderSlot.captured(builder)
            assertEquals(
                SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_RECOVERY_FOCUSED,
                builder.lastRecalcSleepScoreWeightProfile,
            )
            assertEquals(9.5f, builder.lastRecalcGoalSleepHours)
            assertEquals(115, builder.lastRecalcHypersomniaOnsetPercent)
            zipFile.delete()
        }

    @Test
    fun applyRestore_missingLastRecalcFieldsLeaveThemUnset() =
        runTest {
            val zipFile = createBackupZipFile("last_recalc_absent_backup.zip", createValidBackupJson())

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder = UserPreferencesProto.newBuilder()
            builderSlot.captured(builder)
            assertTrue(!builder.hasLastRecalcSleepScoreWeightProfile())
            assertTrue(!builder.hasLastRecalcGoalSleepHours())
            assertTrue(!builder.hasLastRecalcHypersomniaOnsetPercent())
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

    @Test
    fun applyRestore_oldFormatBackupWithoutVitalsKeysLeavesExistingVitalsUntouched() =
        runTest {
            db.weightRecordDao().upsertAll(
                listOf(
                    app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity(
                        id = "existing_weight",
                        timestampMs = 5000L,
                        weightKg = 72.0f,
                    ),
                ),
            )

            // createValidBackupJson() has no "weightRecords"/"bodyFatRecords"/etc keys, matching
            // every backup created before this table set was added to the export.
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("old_format_no_vitals_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            val remainingWeights = db.weightRecordDao().getSince(0)
            assertEquals(1, remainingWeights.size)
            assertEquals("existing_weight", remainingWeights.single().id)
            zipFile.delete()
        }

    @Test
    fun applyRestore_newFormatBackupReplacesExistingVitalsWithBackupContents() =
        runTest {
            db.weightRecordDao().upsertAll(
                listOf(
                    app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity(
                        id = "stale_weight",
                        timestampMs = 5000L,
                        weightKg = 72.0f,
                    ),
                ),
            )

            val json = createValidBackupJson()
            json.put(
                "weightRecords",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "restored_weight")
                            put("timestampMs", 9000L)
                            put("weightKg", 68.2)
                        },
                    )
                },
            )
            val zipFile = createBackupZipFile("new_format_vitals_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            val remainingWeights = db.weightRecordDao().getSince(0)
            assertEquals(1, remainingWeights.size)
            assertEquals("restored_weight", remainingWeights.single().id)
            zipFile.delete()
        }
}
