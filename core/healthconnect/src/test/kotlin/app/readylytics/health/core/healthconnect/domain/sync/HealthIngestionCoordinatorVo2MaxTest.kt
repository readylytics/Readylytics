package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
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
            coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns
                ReadOutcome.Available(listOf(domainRecord))

            val batchSlot = slot<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(batchSlot)) } returns Unit

            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore())
            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(),
            )

            coVerify(exactly = 1) { hcRepo.readVo2MaxRecords(any(), any()) }
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

            coVerify(exactly = 0) { hcRepo.readVo2MaxRecords(any(), any()) }
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
            coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns
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
            coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns
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
            coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns
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

    private fun stubEmptyReads(hcRepo: HealthConnectRepository) {
        coEvery { hcRepo.readSleepSessions(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readWeightRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
    }
}
