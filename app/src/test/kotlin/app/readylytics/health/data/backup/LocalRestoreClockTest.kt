package app.readylytics.health.data.backup

import android.net.Uri
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.util.RetentionBounds
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins [LocalRestoreManager] and [RestoreRecommendationCoverageChecker] to the injected clock. */
@RunWith(RobolectricTestRunner::class)
class LocalRestoreClockTest : LocalRestoreManagerTestBase() {
    @Test
    fun applyRestore_stampsAuditEventsWithInjectedClockInstant() =
        runTest {
            val zipFile = createBackupZipFile("clock_audit_backup.zip", createValidBackupJson())

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            assertTrue(auditTrailRepository.events.isNotEmpty())
            assertEquals(setOf(clock.instant()), auditTrailRepository.events.map { it.occurredAt }.toSet())
            zipFile.delete()
        }

    @Test
    fun applyRestore_summaryExactlyOnRetentionCutoffIsRetainedAndSchedulesRecompute() =
        runTest {
            assertRecomputeScheduledForSummaryAt(offsetFromCutoffMs = 0L, expectedCalls = 1)
        }

    @Test
    fun applyRestore_summaryOneMillisecondBeforeRetentionCutoffDoesNotScheduleRecompute() =
        runTest {
            assertRecomputeScheduledForSummaryAt(offsetFromCutoffMs = -1L, expectedCalls = 0)
        }

    private suspend fun assertRecomputeScheduledForSummaryAt(
        offsetFromCutoffMs: Long,
        expectedCalls: Int,
    ) {
        val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 30)
        coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
        val cutoffMs = checkNotNull(RetentionBounds.resolveRetentionCutoffMs(prefs, clock.instant()))
        val json = createValidBackupJson()
        val summariesJson =
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("dateMidnightMs", cutoffMs + offsetFromCutoffMs)
                        put("sleepScore", 80.0)
                    },
                )
            }
        json.put("dailySummaries", summariesJson)
        json.getJSONObject("rowCounts").put("dailySummaries", summariesJson.length())
        val zipFile = createBackupZipFile("cutoff_boundary_${offsetFromCutoffMs}_backup.zip", json)

        val result = manager.applyRestore(Uri.fromFile(zipFile))

        assertTrue(result is RestoreResult.SuccessRequiresRestart)
        coVerify(exactly = expectedCalls) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
        zipFile.delete()
    }
}
