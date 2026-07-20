package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.scoring.TrimpModel
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ResyncCheckpointResumeTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = InMemoryResyncCheckpointStore()
    private val baselineTokens = mapOf(HealthDataType.SLEEP to "baseline-sleep-token")

    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coEvery { changeSynchronizer.captureChangesTokens() } returns baselineTokens
        coEvery { changeSynchronizer.commitTokens(any()) } returns Unit
        useCase =
            ResyncRangeUseCase(
                settingsRepo = settingsRepo,
                sessionLinkReconciler = sessionLinkReconciler,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                healthIngestionStore = healthIngestionStore,
                ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore),
                stepCountFetcher = StepCountFetcher(hcRepo),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo),
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun `resyncRange resumes ingest from saved chunk checkpoint`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 3)
            val resumedChunkStart = LocalDate.of(2024, 7, 1)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = resumedChunkStart,
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                )

            val sleepFromSlot = slot<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromSlot), any()) } returns emptyList()

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(
                resumedChunkStart.minusDays(1).atStartOfDay(zoneId).toInstant(),
                sleepFromSlot.captured,
            )
        }

    @Test
    fun `resyncRange resumes recompute from saved day and reports completed progress`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 4)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.RECOMPUTE,
                    nextDate = startDate.plusDays(2),
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                )
            val progress = mutableListOf<Triple<ResyncPhase, Int, Int>>()

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = { phase, current, total ->
                    progress += Triple(phase, current, total)
                },
            )

            assertEquals(Triple(ResyncPhase.RECOMPUTE, 2, 4), progress.first())
            coVerify(exactly = 0) { scoringRepository.computeAndPersistDailySummary(startDate, any(), any()) }
            coVerify(
                exactly = 0,
            ) { scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any(), any()) }
            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(2), any(), any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any())
            }
        }

    @Test
    fun `resyncRange discards mismatched checkpoint and restarts from requested range`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 3)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate.minusDays(10),
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = LocalDate.of(2024, 7, 1),
                    selectionHash = "stale",
                    baselineChangeTokens = baselineTokens,
                )

            val sleepFromInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromInstants), any()) } returns emptyList()

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(
                startDate.minusDays(1).atStartOfDay(zoneId).toInstant(),
                sleepFromInstants.first(),
            )
        }

    @Test
    fun `full resync rejects matching checkpoint without baseline tokens and restarts ingest`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 3)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = LocalDate.of(2024, 7, 1),
                    selectionHash = "",
                    baselineChangeTokens = emptyMap(),
                )

            val sleepFromSlot = slot<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromSlot), any()) } throws
                IllegalStateException("stop after checkpoint initialization")

            val result =
                useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(false, result.isSuccess)
            assertEquals(
                startDate.minusDays(1).atStartOfDay(zoneId).toInstant(),
                sleepFromSlot.captured,
            )
            coVerify(exactly = 1) { changeSynchronizer.captureChangesTokens() }
            assertEquals(ResyncPhase.INGEST, checkpointStore.value?.phase)
            assertEquals(startDate, checkpointStore.value?.nextDate)
            assertEquals(baselineTokens, checkpointStore.value?.baselineChangeTokens)
        }

    @Test
    fun `resyncRange captures baseline tokens before ingest and promotes them after recompute`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)

            useCase.run(startDate = startDate, endDate = startDate, chunkDays = 30, onProgress = null)

            coVerifyOrder {
                changeSynchronizer.captureChangesTokens()
                hcRepo.readSleepSessions(any(), any())
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any())
                changeSynchronizer.commitTokens(baselineTokens)
            }
            assertEquals(null, checkpointStore.value)
        }

    @Test
    fun `resyncRange keeps checkpoint and tokens when recompute fails`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            coEvery { scoringRepository.computeAndPersistDailySummary(startDate, any(), any()) } throws
                IllegalStateException("scoring failed")

            val result = useCase.run(startDate = startDate, endDate = startDate, chunkDays = 30, onProgress = null)

            assertEquals(false, result.isSuccess)
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
            assertEquals(ResyncPhase.RECOMPUTE, checkpointStore.value?.phase)
            assertEquals(startDate, checkpointStore.value?.nextDate)
        }

    @Test
    fun `recompute resumes with the same scoring preferences without Health Connect tokens`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val preferences = MutableStateFlow(UserPreferences(trimpModel = TrimpModel.BANISTER))
            every { settingsRepo.userPreferences } returns preferences
            coEvery {
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any(), any())
            } throws IllegalStateException("scoring failed")

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            val failedCheckpoint = requireNotNull(checkpointStore.value)
            assertEquals(ResyncPhase.RECOMPUTE, failedCheckpoint.phase)
            assertEquals(startDate.plusDays(1), failedCheckpoint.nextDate)
            assertEquals(emptyMap<HealthDataType, String>(), failedCheckpoint.baselineChangeTokens)

            clearMocks(scoringRepository, answers = false, recordedCalls = true)
            coEvery { scoringRepository.computeAndPersistDailySummary(any(), any(), any()) } returns Unit

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            coVerify(exactly = 0) {
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any())
            }
            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any(), any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any())
            }
            coVerify(exactly = 0) { changeSynchronizer.captureChangesTokens() }
            coVerify(exactly = 0) { changeSynchronizer.applyPendingChanges() }
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
        }

    @Test
    fun `recompute restarts from start when a scoring preference changes`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val preferences = MutableStateFlow(UserPreferences(trimpModel = TrimpModel.BANISTER))
            every { settingsRepo.userPreferences } returns preferences
            coEvery {
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any(), any())
            } throws IllegalStateException("scoring failed")

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            val failedCheckpoint = requireNotNull(checkpointStore.value)
            assertEquals(ResyncPhase.RECOMPUTE, failedCheckpoint.phase)
            assertEquals(startDate.plusDays(1), failedCheckpoint.nextDate)
            assertEquals(emptyMap<HealthDataType, String>(), failedCheckpoint.baselineChangeTokens)

            clearMocks(scoringRepository, answers = false, recordedCalls = true)
            preferences.value = UserPreferences(trimpModel = TrimpModel.CHENG)
            coEvery { scoringRepository.computeAndPersistDailySummary(any(), any(), any()) } returns Unit

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any())
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any(), any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any())
            }
            assertEquals(null, checkpointStore.value)
        }

    private class InMemoryResyncCheckpointStore : ResyncCheckpointStore {
        private val state = MutableStateFlow<ResyncCheckpoint?>(null)

        var value: ResyncCheckpoint?
            get() = state.value
            set(value) {
                state.value = value
            }

        override val checkpoint: Flow<ResyncCheckpoint?> = state

        override suspend fun save(checkpoint: ResyncCheckpoint) {
            state.value = checkpoint
        }

        override suspend fun clear() {
            state.value = null
        }
    }
}
