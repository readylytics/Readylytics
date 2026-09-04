package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
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
import kotlin.test.assertTrue

class HealthIngestionCoordinatorVo2MaxTest {
    @Test
    fun `ingestWindow ingests and persists Vo2Max records when permission is granted`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
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
            coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns listOf(domainRecord)

            val batchSlot = slot<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(batchSlot)) } returns Unit

            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore)
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
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)

            coEvery { hcRepo.hasVo2MaxPermission() } returns false

            val batchSlot = slot<HealthIngestionBatch>()
            coEvery { healthIngestionStore.persist(capture(batchSlot)) } returns Unit

            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore)
            coordinator.ingestWindow(
                windowStart = Instant.parse("2026-09-03T00:00:00Z"),
                windowEnd = Instant.parse("2026-09-03T23:59:59Z"),
                prefs = UserPreferences(),
            )

            coVerify(exactly = 0) { hcRepo.readVo2MaxRecords(any(), any()) }
            assertTrue(batchSlot.isCaptured)
            assertTrue(batchSlot.captured.vo2MaxSamples.isEmpty())
        }
}
