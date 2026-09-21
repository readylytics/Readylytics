package app.readylytics.health.data.backup

import android.net.Uri
import app.readylytics.health.core.database.data.mapper.WorkoutRecommendationCodec
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationDecision
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationSnapshot
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationState
import app.readylytics.health.data.preferences.UserPreferencesProto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertTrue

/**
 * Task 5: covers [LocalRestoreManager]'s restore-time workout-recommendation coverage check
 * (delegated to [RestoreRecommendationCoverageChecker]). Split out of
 * [LocalRestoreApplicationTest] (which sits near this codebase's 800-line file ceiling) rather
 * than growing that file further.
 */
@RunWith(RobolectricTestRunner::class)
class LocalRestoreRecommendationCoverageTest : LocalRestoreManagerTestBase() {
    @Test
    fun applyRestore_oldBackupMissingRecommendationPayloadTriggersRecomputeDespiteCurrentScoringVersionMarker() =
        runTest {
            val json = createValidBackupJson()
            // Internally inconsistent on purpose: the backup's preferences claim the current
            // scoring version even though its daily summary carries no recommendation payload --
            // a backup can predate a rule-version bump without predating the scoringVersion bump.
            json.getJSONObject("preferences").put("scoringVersion", SettingsDefaults.CURRENT_SCORING_VERSION)
            val summariesJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", 1779926400000L)
                            put("sleepScore", 85.0)
                        },
                    )
                }
            json.put("dailySummaries", summariesJson)
            json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
            val zipFile = createBackupZipFile("old_backup_missing_recommendation.zip", json)

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 1) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_summariesWithValidRecommendationPayloadDoesNotScheduleRecompute() =
        runTest {
            val json = createValidBackupJson()
            val snapshot =
                WorkoutRecommendationSnapshot(
                    wakeSessionId = null,
                    wakeTimeMs = null,
                    decision = WorkoutRecommendationDecision(state = WorkoutRecommendationState.REST),
                )
            val summariesJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", 1779926400000L)
                            put("workoutRecommendationJson", WorkoutRecommendationCodec.encode(snapshot))
                        },
                    )
                }
            json.put("dailySummaries", summariesJson)
            json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
            val zipFile = createBackupZipFile("valid_recommendation_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 0) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_partiallyCoveredSummariesDoNotScheduleRecompute() =
        runTest {
            // Final-review fix (Finding 2): a *single* day without a payload is not evidence the
            // backup predates the feature, and it is not necessarily repairable -- a day whose
            // morning sleep-metrics pass fails legitimately yields no snapshot, deterministically,
            // for the same stored data. Under an "any row is missing one" test, restoring this
            // database would schedule another full recompute-only pass every single time, forever,
            // for a day whose answer can never change.
            val json = createValidBackupJson()
            val snapshot =
                WorkoutRecommendationSnapshot(
                    wakeSessionId = null,
                    wakeTimeMs = null,
                    decision = WorkoutRecommendationDecision(state = WorkoutRecommendationState.REST),
                )
            val summariesJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", 1779926400000L)
                            put("workoutRecommendationJson", WorkoutRecommendationCodec.encode(snapshot))
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", 1780012800000L)
                            put("sleepScore", 80.0)
                        },
                    )
                }
            json.put("dailySummaries", summariesJson)
            json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
            val zipFile = createBackupZipFile("partially_covered_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 0) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_emptyDailySummariesRestoresSuccessfullyWithoutSchedulingRecompute() =
        runTest {
            // No "dailySummaries" content at all -- the oldest possible backup shape, predating
            // both this column and the recommendation feature entirely. Must still restore fine.
            val zipFile = createBackupZipFile("no_summaries_backup.zip", createValidBackupJson())

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 0) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            zipFile.delete()
        }

    // --- Fix round 1 (review findings) ---

    @Test
    fun applyRestore_summaryOutsideRetentionWithMissingPayloadDoesNotScheduleRecompute() =
        runTest {
            // Task 5 fix round 1 (Minor #3): a row this retention window will never see again
            // can never be repaired by the retention-bounded recompute this would schedule, so it
            // must never be the reason one gets scheduled.
            val retentionDays = 30
            coEvery { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = retentionDays))

            val json = createValidBackupJson()
            val outsideRetentionMs =
                Instant.now().minus(retentionDays + 10L, ChronoUnit.DAYS).toEpochMilli()
            val summariesJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", outsideRetentionMs)
                            put("sleepScore", 80.0)
                        },
                    )
                }
            json.put("dailySummaries", summariesJson)
            json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
            val zipFile = createBackupZipFile("outside_retention_missing_payload_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 0) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_summaryWithinRetentionWithMissingPayloadSchedulesRecompute() =
        runTest {
            // Companion to the test above: proves the retention bound actually filters (rather
            // than always skipping) by using an otherwise-identical row that IS retained.
            val retentionDays = 30
            coEvery { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = retentionDays))

            val json = createValidBackupJson()
            val withinRetentionMs = Instant.now().minus(5, ChronoUnit.DAYS).toEpochMilli()
            val summariesJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", withinRetentionMs)
                            put("sleepScore", 80.0)
                        },
                    )
                }
            json.put("dailySummaries", summariesJson)
            json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
            val zipFile = createBackupZipFile("within_retention_missing_payload_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 1) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_schedulesRecomputeOnlyAfterPreferencesRestoreSucceeds() =
        runTest {
            // Task 5 fix round 1 (Minor #2): the coverage check must read the just-restored
            // preferences, not preferences that are still mid-write, so it must run after
            // restorePreferences (settingsRepo.batchUpdate) commits -- never before.
            val json = createValidBackupJson()
            val summariesJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", 1779926400000L)
                            put("sleepScore", 80.0)
                        },
                    )
                }
            json.put("dailySummaries", summariesJson)
            json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
            val zipFile = createBackupZipFile("ordering_backup.zip", json)

            val builderSlot = io.mockk.slot<UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerifyOrder {
                settingsRepo.batchUpdate(any())
                workerScheduler.scheduleResyncWorker(recomputeOnly = true)
            }
            zipFile.delete()
        }

    @Test
    fun applyRestore_doesNotScheduleRecomputeWhenPreferencesRestoreFails() =
        runTest {
            // Task R3: When preferences restore fails, maintenance remains active and
            // recompute must NOT be scheduled with stale preferences.
            val json = createValidBackupJson()
            val summariesJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("dateMidnightMs", 1779926400000L)
                            put("sleepScore", 80.0)
                        },
                    )
                }
            json.put("dailySummaries", summariesJson)
            json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
            val zipFile = createBackupZipFile("prefs_fail_no_recompute_backup.zip", json)

            coEvery { settingsRepo.batchUpdate(any()) } throws RuntimeException("prefs fail")

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.PartialSuccessRequiresRestart)
            coVerify(exactly = 0) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
            zipFile.delete()
        }
}
