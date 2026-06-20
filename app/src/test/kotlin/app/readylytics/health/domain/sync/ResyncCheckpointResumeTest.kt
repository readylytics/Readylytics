package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
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
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val rasSourceModeBootstrapUseCase = mockk<RasSourceModeBootstrapUseCase>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = InMemoryResyncCheckpointStore()

    private lateinit var useCase: HealthSyncUseCase

    @Before
    fun setup() {
        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coEvery { changeSynchronizer.refreshTokensAfterFullResync() } returns Unit
        coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummary(LocalDate.of(1970, 1, 1))

        useCase =
            HealthSyncUseCase(
                hcRepo = hcRepo,
                healthIngestionStore = healthIngestionStore,
                settingsRepo = settingsRepo,
                scoringRepository = scoringRepository,
                transactionRunner = transactionRunner,
                sessionLinkReconciler = sessionLinkReconciler,
                rasSourceModeBootstrapUseCase = rasSourceModeBootstrapUseCase,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
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
                )

            val sleepFromSlot = slot<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromSlot), any()) } returns emptyList()

            useCase.resyncRange(startDate = startDate, endDate = endDate, chunkDays = 30)

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
                )
            val progress = mutableListOf<Pair<Int, Int>>()

            useCase.resyncRange(startDate = startDate, endDate = endDate) { current, total ->
                progress += current to total
            }

            assertEquals(2 to 4, progress.first())
            coVerify(exactly = 0) { scoringRepository.computeDailySummary(startDate) }
            coVerify(exactly = 0) { scoringRepository.computeDailySummary(startDate.plusDays(1)) }
            coVerifyOrder {
                scoringRepository.computeDailySummary(startDate.plusDays(2))
                scoringRepository.computeDailySummary(endDate)
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
                )

            val sleepFromInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromInstants), any()) } returns emptyList()

            useCase.resyncRange(startDate = startDate, endDate = endDate, chunkDays = 30)

            assertEquals(
                startDate.minusDays(1).atStartOfDay(zoneId).toInstant(),
                sleepFromInstants.first(),
            )
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
