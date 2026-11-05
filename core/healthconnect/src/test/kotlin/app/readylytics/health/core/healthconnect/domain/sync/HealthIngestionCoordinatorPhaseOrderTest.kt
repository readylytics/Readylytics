package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ExerciseSessionRead
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.TypeScanState
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Characterization lock for the phase order of [HealthIngestionCoordinator.ingestWindow]
 * (bulk fetch + persist -> HR -> HRV -> steps -> bulk scan staging -> deletion reconcile), pinned
 * BEFORE the coordinator is split into phase collaborators so the split cannot silently reorder it.
 */
class HealthIngestionCoordinatorPhaseOrderTest {
    private val events = mutableListOf<String>()
    private val scanId = ScanIdentity("run-a", "0")
    private val windowStart = Instant.EPOCH
    private val windowEnd = Instant.EPOCH.plusSeconds(3600)
    private val reconciled = mutableListOf<CompleteTypeScan>()

    /** Records stage/complete calls in [events] while delegating state to the in-memory store. */
    private inner class RecordingStaging(
        private val inner: ScanStagingStore = FakeScanStagingStore(),
    ) : ScanStagingStore by inner {
        override suspend fun stageIds(scan: ScanIdentity, type: HealthDataType, ids: Collection<String>) {
            events += "stage:${type.name}"
            inner.stageIds(scan, type, ids)
        }

        override suspend fun markTypeScanComplete(scan: ScanIdentity, type: HealthDataType) {
            events += "complete:${type.name}"
            inner.markTypeScanComplete(scan, type)
        }
    }

    private fun recordingStore(): HealthIngestionStore {
        val store = mockk<HealthIngestionStore>(relaxed = true)
        coEvery { store.persist(any()) } coAnswers { events += persistLabel(firstArg()) }
        coEvery { store.reconcileWindow(any(), any()) } coAnswers {
            val scan = firstArg<CompleteTypeScan>()
            reconciled += scan
            events += "reconcile:${scan.type.name}"
            null
        }
        return store
    }

    private fun persistLabel(batch: HealthIngestionBatch): String =
        when {
            batch.sleepSessions.isNotEmpty() && batch.workouts.isNotEmpty() -> "persist:bulk"
            batch.stepRecords.isNotEmpty() -> "persist:steps"
            else -> "persist:empty"
        }

    private fun sleep() =
        DomainSleepSessionRecord(
            id = "sleep-1",
            startTime = windowStart,
            endTime = windowStart.plusSeconds(600),
            startZoneOffsetSeconds = 0,
            endZoneOffsetSeconds = 0,
            deviceName = "Pixel Watch",
            stages = emptyList(),
        )

    private fun workout() =
        DomainExerciseSessionRecord(
            id = "workout-1",
            startTime = windowStart,
            endTime = windowStart.plusSeconds(600),
            exerciseType = "RUNNING",
            deviceName = "Pixel Watch",
        )

    private fun steps(id: String) =
        DomainStepsRecord(id, windowStart, windowStart.plusSeconds(60), 10L, "Pixel Watch")

    /** Happy-path repo: sleep + workout in the bulk read; one HR, one HRV and [stepPages] pages. */
    private fun repo(
        hrDenied: Boolean = false,
        stepPages: List<DomainStepsRecord> = listOf(steps("s1")),
        afterStepPage: suspend () -> Unit = {},
    ): HealthConnectRepository {
        val repo = mockk<HealthConnectRepository>()
        coEvery { repo.hasVo2MaxPermission() } returns false
        coEvery { repo.readSleepSessions(any(), any(), any()) } returns ReadOutcome.Available(listOf(sleep()))
        coEvery { repo.readExerciseSessionsWithCompletion(any(), any(), any()) } returns
            ExerciseSessionRead(ReadOutcome.Available(listOf(workout())))
        coEvery { repo.readWeightRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { repo.readBodyFatRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { repo.readBloodPressureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { repo.readOxygenSaturationRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { repo.readBodyTemperatureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { repo.readHeartRateSamplesPaged(any(), any(), any(), any(), any()) } coAnswers {
            if (hrDenied) return@coAnswers ReadOutcome.Denied
            val onPage = arg<suspend (List<DomainHeartRateRecord>, String?) -> Unit>(4)
            val sample = DomainHeartRateSample(windowStart.plusSeconds(1), 70)
            onPage(listOf(DomainHeartRateRecord("hr-1", "Pixel Watch", listOf(sample))), null)
            ReadOutcome.Available(Unit)
        }
        coEvery { repo.readHrvSamplesPaged(any(), any(), any(), any(), any()) } coAnswers {
            val onPage = arg<suspend (List<DomainHrvRecord>, String?) -> Unit>(4)
            onPage(listOf(DomainHrvRecord("hrv-1", windowStart.plusSeconds(1), 40f, "Pixel Watch")), null)
            ReadOutcome.Available(Unit)
        }
        coEvery { repo.readStepsRecordsPaged(any(), any(), any(), any()) } coAnswers {
            val onPage = arg<suspend (List<DomainStepsRecord>) -> Unit>(3)
            for (page in stepPages) {
                onPage(listOf(page))
                afterStepPage()
            }
            ReadOutcome.Available(Unit)
        }
        return repo
    }

    private suspend fun HealthIngestionCoordinator.ingest(
        reconcileDeletions: Boolean = true,
        windowBudgetMs: Long = 3 * 60_000L,
    ) = ingestWindow(
        windowStart = windowStart,
        windowEnd = windowEnd,
        prefs = UserPreferences(),
        scanIdentity = scanId,
        windowBudgetMs = windowBudgetMs,
        reconcileDeletions = reconcileDeletions,
    )

    @Test
    fun bulkIsPersistedBeforeHeartRateIsStreamed_thenHrv_thenSteps_thenReconcile() =
        runTest {
            val coordinator = HealthIngestionCoordinator(repo(), recordingStore(), RecordingStaging())

            coordinator.ingest()

            val bulkPersist = events.indexOf("persist:bulk")
            val hrStaged = events.indexOf("stage:HEART_RATE")
            val hrvStaged = events.indexOf("stage:HRV")
            val stepsStaged = events.indexOf("stage:STEPS")
            assertTrue(bulkPersist >= 0, "bulk batch never persisted: $events")
            assertTrue(bulkPersist < hrStaged, "bulk must precede first HR page: $events")
            assertTrue(hrStaged < hrvStaged, "HR must precede HRV: $events")
            assertTrue(hrvStaged < stepsStaged, "HRV must precede steps: $events")
            val firstReconcile = events.indexOfFirst { it.startsWith("reconcile:") }
            val lastComplete = events.indexOfLast { it.startsWith("complete:") }
            assertTrue(firstReconcile > lastComplete, "reconcile must follow every scan completion: $events")
            assertEquals(
                setOf(
                    HealthDataType.SLEEP, HealthDataType.EXERCISE, HealthDataType.WEIGHT,
                    HealthDataType.BODY_FAT, HealthDataType.BLOOD_PRESSURE, HealthDataType.OXYGEN_SATURATION,
                    HealthDataType.BODY_TEMPERATURE, HealthDataType.HEART_RATE, HealthDataType.HRV,
                    HealthDataType.STEPS,
                ),
                reconciled.map { it.type }.toSet(),
            )
        }

    @Test
    fun reconcileDeletionsFalse_neverCallsReconcileWindow() =
        runTest {
            val coordinator = HealthIngestionCoordinator(repo(), recordingStore(), RecordingStaging())

            val result = coordinator.ingest(reconcileDeletions = false)

            assertTrue(events.none { it.startsWith("reconcile:") }, "reconcile ran: $events")
            assertTrue(reconciled.isEmpty())
            assertNull(result.affectedRange)
            assertTrue(HealthDataType.STEPS in result.completedTypes, "scans must still complete")
        }

    @Test
    fun stepsScanOnlyCompletesAfterFinalPage() =
        runTest {
            val staging = RecordingStaging()
            val statesAfterPage = mutableListOf<TypeScanState?>()
            val repo =
                repo(stepPages = listOf(steps("s1"), steps("s2"))) {
                    statesAfterPage += staging.stateOf(scanId, HealthDataType.STEPS)
                    events += "steps:page-delivered"
                }

            HealthIngestionCoordinator(repo, recordingStore(), staging).ingest()

            assertEquals<List<TypeScanState?>>(listOf(TypeScanState.SCANNING, TypeScanState.SCANNING), statesAfterPage)
            val finalPage = events.lastIndexOf("steps:page-delivered")
            assertEquals(2, events.count { it == "persist:steps" })
            assertTrue(events.indexOf("complete:STEPS") > finalPage, "steps completed early: $events")
            assertEquals(TypeScanState.COMPLETE, staging.stateOf(scanId, HealthDataType.STEPS))
        }

    @Test
    fun windowTimeout_mapsToHealthConnectWindowTimeoutException_notCancellation() =
        runTest {
            val repo = repo()
            coEvery { repo.readSleepSessions(any(), any(), any()) } coAnswers {
                delay(200L)
                ReadOutcome.Available(emptyList())
            }
            val coordinator = HealthIngestionCoordinator(repo, recordingStore(), RecordingStaging())

            val thrown =
                assertFailsWith<HealthConnectWindowTimeoutException> {
                    coordinator.ingest(windowBudgetMs = 100L)
                }

            assertEquals(windowStart, thrown.windowStart)
            assertEquals(windowEnd, thrown.windowEnd)
            assertFalse((thrown as Throwable) is CancellationException)
            assertTrue(events.none { it.startsWith("reconcile:") || it.startsWith("stage:") }, "$events")
        }

    @Test
    fun cancellation_propagatesUnchanged() =
        runTest {
            val repo = repo()
            coEvery { repo.readHrvSamplesPaged(any(), any(), any(), any(), any()) } throws CancellationException("stop")
            val coordinator = HealthIngestionCoordinator(repo, recordingStore(), RecordingStaging())

            val thrown = assertFailsWith<CancellationException> { coordinator.ingest() }

            assertEquals("stop", thrown.message)
            assertFalse((thrown as Throwable) is HealthConnectWindowTimeoutException)
            assertTrue(events.none { it.startsWith("reconcile:") || it == "complete:STEPS" }, "$events")
        }

    @Test
    fun deniedHeartRate_leavesScanIncomplete_andReconcilesOnlyCompletedTypes() =
        runTest {
            val staging = RecordingStaging()
            val coordinator = HealthIngestionCoordinator(repo(hrDenied = true), recordingStore(), staging)

            val result = coordinator.ingest()

            assertEquals(TypeScanState.SCANNING, staging.stateOf(scanId, HealthDataType.HEART_RATE))
            val reconciledTypes = reconciled.map { it.type }.toSet()
            assertFalse(HealthDataType.HEART_RATE in reconciledTypes, "HR reconciled while incomplete: $events")
            assertFalse(HealthDataType.HEART_RATE in result.completedTypes)
            assertTrue(
                reconciledTypes.containsAll(setOf(HealthDataType.HRV, HealthDataType.STEPS, HealthDataType.SLEEP)),
                "completed types must still reconcile: $reconciledTypes",
            )
        }
}
