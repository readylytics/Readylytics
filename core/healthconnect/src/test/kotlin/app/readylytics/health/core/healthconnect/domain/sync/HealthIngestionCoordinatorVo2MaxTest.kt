package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.TypeScanState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthIngestionCoordinatorVo2MaxTest {
    @Test
    fun `ingestWindow ingests and persists Vo2Max records when permission is granted`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)

            coEvery { hcRepo.hasVo2MaxPermission() } returns true
            val vo2Time = Instant.parse("2026-09-03T10:00:00Z")
            val domainRecord =
                DomainVo2MaxRecord(
                    id = "vo2-test-1",
                    time = vo2Time,
                    vo2MillilitersPerMinuteKilogram = 48.5,
                    measurementMethod = 1,
                    deviceName = "Pixel Watch",
                )
            coEvery { hcRepo.readVo2MaxRecords(any(), any(), any()) } returns
                ReadOutcome.Available(listOf(domainRecord))

            val batchSlot = slot<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(batchSlot)) } returns Unit

            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore())
            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(),
            )

            coVerify(exactly = 1) { hcRepo.readVo2MaxRecords(any(), any(), any()) }
            assertTrue(batchSlot.isCaptured)
            val vo2Samples = batchSlot.captured.vo2MaxSamples
            assertEquals(1, vo2Samples.size)
            assertEquals("vo2-test-1", vo2Samples[0].id)
            assertEquals(vo2Time.toEpochMilli(), vo2Samples[0].timestampMs)
            assertEquals(48.5f, vo2Samples[0].vo2Max)
            assertEquals(1, vo2Samples[0].measurementMethod)
            assertEquals("Pixel Watch", vo2Samples[0].deviceName)
        }

    @Test
    fun `ingestWindow skips Vo2Max records when permission is not granted`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)

            coEvery { hcRepo.hasVo2MaxPermission() } returns false

            val batchSlot = slot<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(batchSlot)) } returns Unit

            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore())
            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(),
            )

            coVerify(exactly = 0) { hcRepo.readVo2MaxRecords(any(), any(), any()) }
            assertTrue(batchSlot.isCaptured)
            assertTrue(batchSlot.captured.vo2MaxSamples.isEmpty())
        }

    @Test
    fun `ingestWindow includes VO2_MAX in completedTypes only when the scan is available`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
            coEvery { healthIngestionStore.reconcileWindow(any(), any()) } returns null

            coEvery { hcRepo.hasVo2MaxPermission() } returns true
            coEvery { hcRepo.readVo2MaxRecords(any(), any(), any()) } returns
                ReadOutcome.Available(
                    listOf(
                        DomainVo2MaxRecord(
                            id = "vo2-available",
                            time = Instant.parse("2026-09-03T10:00:00Z"),
                            vo2MillilitersPerMinuteKilogram = 48.5,
                            measurementMethod = null,
                            deviceName = "Watch A",
                        ),
                    ),
                )

            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore())
            val result =
                coordinator.ingestWindow(
                    windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                    windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                    prefs = UserPreferences(),
                )

            assertTrue(HealthDataType.VO2_MAX in result.completedTypes)
        }

    @Test
    fun `ingestWindow excludes VO2_MAX from completedTypes when permission is denied`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
            coEvery { healthIngestionStore.reconcileWindow(any(), any()) } returns null
            coEvery { hcRepo.hasVo2MaxPermission() } returns false

            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore())
            val result =
                coordinator.ingestWindow(
                    windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                    windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                    prefs = UserPreferences(),
                )

            assertFalse(HealthDataType.VO2_MAX in result.completedTypes)
        }

    @Test
    fun `ingestWindow filters Vo2Max records to the selected device but reconciles against every raw id`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
            val scanSlot = mutableListOf<CompleteTypeScan>()
            coEvery { healthIngestionStore.reconcileWindow(capture(scanSlot), any()) } returns null

            coEvery { hcRepo.hasVo2MaxPermission() } returns true
            val recordA =
                DomainVo2MaxRecord(
                    id = "vo2-device-a",
                    time = Instant.parse("2026-09-03T10:00:00Z"),
                    vo2MillilitersPerMinuteKilogram = 48.5,
                    measurementMethod = null,
                    deviceName = "Watch A",
                )
            val recordB =
                DomainVo2MaxRecord(
                    id = "vo2-device-b",
                    time = Instant.parse("2026-09-03T11:00:00Z"),
                    vo2MillilitersPerMinuteKilogram = 50.0,
                    measurementMethod = null,
                    deviceName = "Watch B",
                )
            coEvery { hcRepo.readVo2MaxRecords(any(), any(), any()) } returns
                ReadOutcome.Available(listOf(recordA, recordB))

            val batchSlot = slot<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(batchSlot)) } returns Unit

            val staging = InMemoryScanStagingStore()
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, staging = staging)
            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(deviceByDataType = mapOf(HealthDataType.VO2_MAX.name to "Watch A")),
            )

            // Bulk persist is device-filtered -- only the selected device's sample is stored.
            val persistedIds = batchSlot.captured.vo2MaxSamples.map { it.id }
            assertEquals(listOf("vo2-device-a"), persistedIds)

            // The reconciliation scan still carries every raw HC id regardless of device
            // selection, otherwise a non-selected-device record still present in HC would be
            // wrongly treated as deleted (mirrors WEIGHT/BODY_FAT/etc).
            val vo2Scan = scanSlot.single { it.type == HealthDataType.VO2_MAX }
            assertEquals("DAILY_SYNC", vo2Scan.scan.runId)
            assertEquals(setOf("vo2-device-a", "vo2-device-b"), staging.stagedIds(vo2Scan.scan, HealthDataType.VO2_MAX))
        }

    @Test
    fun `ingestWindow keeps two Vo2Max records with a stable timestamp tie distinct`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
            val scanSlot = mutableListOf<CompleteTypeScan>()
            coEvery { healthIngestionStore.reconcileWindow(capture(scanSlot), any()) } returns null

            coEvery { hcRepo.hasVo2MaxPermission() } returns true
            val sharedTime = Instant.parse("2026-09-03T10:00:00Z")
            val recordA =
                DomainVo2MaxRecord(
                    id = "vo2-tie-a",
                    time = sharedTime,
                    vo2MillilitersPerMinuteKilogram = 48.5,
                    measurementMethod = null,
                    deviceName = "Watch A",
                )
            val recordB =
                DomainVo2MaxRecord(
                    id = "vo2-tie-b",
                    time = sharedTime,
                    vo2MillilitersPerMinuteKilogram = 50.0,
                    measurementMethod = null,
                    deviceName = "Watch A",
                )
            coEvery { hcRepo.readVo2MaxRecords(any(), any(), any()) } returns
                ReadOutcome.Available(listOf(recordA, recordB))

            val batchSlot = slot<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(batchSlot)) } returns Unit

            val staging = InMemoryScanStagingStore()
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, staging = staging)
            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(),
            )

            assertEquals(
                setOf("vo2-tie-a", "vo2-tie-b"),
                batchSlot.captured.vo2MaxSamples.map { it.id }.toSet(),
            )
            val vo2Scan = scanSlot.single { it.type == HealthDataType.VO2_MAX }
            assertEquals("DAILY_SYNC", vo2Scan.scan.runId)
            assertEquals(setOf("vo2-tie-a", "vo2-tie-b"), staging.stagedIds(vo2Scan.scan, HealthDataType.VO2_MAX))
        }

    // WP-15: steps is a dense type (a continuously-recorded day can reach tens of thousands of
    // rows), so like HR/HRV it is streamed page-by-page and staged as each page arrives instead of
    // being bulk-fetched and held in memory (HC-001).
    @Test
    fun denseStepsStageEveryProviderId() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            // A session is also present so this test doubles as the regression check that removing
            // steps from RawBulkRecords did not disturb the complete, unfiltered session lists that
            // feed IngestionSessionContext (sleep/workout mapping for HR/HRV tagging).
            stubDenseSessionReads(hcRepo)

            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
            val persistedBatches = mutableListOf<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(persistedBatches)) } returns Unit

            val configuredPageSize = 1_000
            val referenceIds = (0 until 43_200).map { "steps-$it" }
            val maxPageSize = stubDenseStepsPages(hcRepo, referenceIds, configuredPageSize)

            val staging = InMemoryScanStagingStore()
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, staging)
            val scanId = ScanIdentity("run-dense-steps", "0")

            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(),
                scanIdentity = scanId,
            )

            assertEquals(referenceIds.toSet(), staging.stagedIds(scanId, HealthDataType.STEPS))
            assertTrue(maxPageSize[0] <= configuredPageSize)
            assertEquals(TypeScanState.COMPLETE, staging.stateOf(scanId, HealthDataType.STEPS))

            // The complete, unfiltered session lists still reach the bulk batch unaffected by
            // steps moving to its own paged/staged path.
            val sessionBatch = persistedBatches.single { it.sleepSessions.isNotEmpty() || it.workouts.isNotEmpty() }
            assertEquals(listOf("sleep-dense-1"), sessionBatch.sleepSessions.map { it.id })
            assertEquals(listOf("workout-dense-1"), sessionBatch.workouts.map { it.id })
        }

    private fun stubDenseSessionReads(hcRepo: HealthConnectRepository) {
        val sleepSession =
            DomainSleepSessionRecord(
                id = "sleep-dense-1",
                startTime = Instant.parse("2026-09-03T00:00:00Z"),
                endTime = Instant.parse("2026-09-03T08:00:00Z"),
                startZoneOffsetSeconds = 0,
                endZoneOffsetSeconds = 0,
                deviceName = "Phone",
                stages = emptyList(),
            )
        val workoutSession =
            DomainExerciseSessionRecord(
                id = "workout-dense-1",
                startTime = Instant.parse("2026-09-03T10:00:00Z"),
                endTime = Instant.parse("2026-09-03T11:00:00Z"),
                exerciseType = "running",
                deviceName = "Phone",
            )
        coEvery { hcRepo.readSleepSessions(any(), any(), any()) } returns
            ReadOutcome.Available(listOf(sleepSession))
        coEvery { hcRepo.readExerciseSessions(any(), any(), any(), any()) } returns
            ReadOutcome.Available(listOf(workoutSession))
    }

    /** Stubs a multi-page steps read and returns a 1-element array tracking the largest page seen. */
    private fun stubDenseStepsPages(
        hcRepo: HealthConnectRepository,
        referenceIds: List<String>,
        pageSize: Int,
    ): IntArray {
        val maxPageSize = intArrayOf(0)
        coEvery {
            hcRepo.readStepsRecordsPaged(any(), any(), any(), any())
        } coAnswers {
            val onPage = arg<suspend (List<DomainStepsRecord>) -> Unit>(3)
            for (chunk in referenceIds.chunked(pageSize)) {
                maxPageSize[0] = maxOf(maxPageSize[0], chunk.size)
                onPage(
                    chunk.map { id ->
                        DomainStepsRecord(
                            id = id,
                            startTime = Instant.parse("2026-09-03T00:00:00Z"),
                            endTime = Instant.parse("2026-09-03T00:00:01Z"),
                            count = 10L,
                            deviceName = "Phone",
                        )
                    },
                )
            }
            ReadOutcome.Available(Unit)
        }
        return maxPageSize
    }

    /**
     * HC-005: a scan must never be marked COMPLETE unless its entire paged read succeeds -- a
     * denied steps read must leave the scan SCANNING (there is no separate "INCOMPLETE" state; see
     * [TypeScanState]) and must never let the Room-level reconciler (gated on COMPLETE) delete any
     * previously-persisted row. The persisted-batch side of "rows unchanged after denial" is
     * exercised at the Room layer in `RoomHealthIngestionStoreReconcileTest`.
     */
    @Test
    fun deniedPageNeverCompletesScan() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            stubEmptyReads(hcRepo)
            coEvery { hcRepo.readStepsRecordsPaged(any(), any(), any(), any()) } returns ReadOutcome.Denied
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)

            val staging = InMemoryScanStagingStore()
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, staging)
            val scanId = ScanIdentity("run-denied-steps", "0")

            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(),
                scanIdentity = scanId,
            )

            assertTrue(staging.stagedIds(scanId, HealthDataType.STEPS).isEmpty())
            assertEquals(TypeScanState.SCANNING, staging.stateOf(scanId, HealthDataType.STEPS))
            coVerify(exactly = 0) { healthIngestionStore.persist(match { it.stepRecords.isNotEmpty() }) }
        }

    private fun stubEmptyReads(hcRepo: HealthConnectRepository) {
        coEvery { hcRepo.readSleepSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessions(any(), any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readWeightRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readStepsRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readStepsRecordsPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any(), any()) } returns
            ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
    }
}
