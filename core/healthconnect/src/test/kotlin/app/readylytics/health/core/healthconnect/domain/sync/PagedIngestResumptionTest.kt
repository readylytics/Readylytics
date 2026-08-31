package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

class PagedIngestResumptionTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = InMemoryResyncCheckpointStore()
    private val baselineTokens = mapOf(HealthDataType.SLEEP to "baseline-sleep-token")
    private val transactionRunner = RecordingTransactionRunner()

    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coEvery { changeSynchronizer.captureChangesTokens() } returns baselineTokens
        coEvery { changeSynchronizer.commitTokens(any()) } returns Unit
        coEvery { scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any()) } returns
            WalkForwardTrimpContext(TreeMap(), TreeMap())
        coEvery { scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any()) } returns
            WalkForwardBaselineContext(emptyList())
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())

        useCase =
            ResyncRangeUseCase(
                settingsRepo = settingsRepo,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
                sessionLinkReconciler = sessionLinkReconciler,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                healthIngestionStore = healthIngestionStore,
                ingestion =
                    ResyncIngestionDependencies(
                        ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore),
                        stepCountFetcher = StepCountFetcher(hcRepo),
                    ),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun `page 1 of 3 HR reads updates checkpoint with hrPageToken`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            val savedTokens = mutableListOf<String?>()
            checkpointStore.onSave = {
                savedTokens.add(checkpointStore.value?.hrPageToken)
            }

            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), "page-2")
                callback(listOf(mockk(relaxed = true)), "page-3")
                callback(listOf(mockk(relaxed = true)), null)
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            // Verify that intermediate checkpoints recorded page-2 and page-3
            assertEquals(true, savedTokens.contains("page-2"))
            assertEquals(true, savedTokens.contains("page-3"))
        }

    @Test
    fun `resuming ingestion with hrStartPageToken passes token to repository and starts from token`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = startDate,
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                    hrPageToken = "page-2",
                )

            val capturedStartToken = slot<String?>()
            coEvery {
                hcRepo.readHeartRateSamplesPaged(any(), any(), captureNullable(capturedStartToken), any())
            } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), null)
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals("page-2", capturedStartToken.captured)
        }

    @Test
    fun `resuming with hrvStartPageToken skips HR stream and resumes HRV stream from token`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = startDate,
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                    hrPageToken = null,
                    hrvPageToken = "hrv-page-2",
                )

            val capturedHrvStartToken = slot<String?>()
            coEvery {
                hcRepo.readHrvSamplesPaged(any(), any(), captureNullable(capturedHrvStartToken), any())
            } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHrvRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), null)
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            // HR stream must be skipped because HRV was already in progress
            coVerify(exactly = 0) { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) }
            assertEquals("hrv-page-2", capturedHrvStartToken.captured)
        }

    @Test
    fun `chunk timeout clears active page tokens`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 10)

            val savedCheckpoints = mutableListOf<ResyncCheckpoint>()
            checkpointStore.onSave = {
                checkpointStore.value?.let { savedCheckpoints.add(it) }
            }

            var callCount = 0
            coEvery { hcRepo.readSleepSessions(any(), any()) } coAnswers {
                callCount++
                if (callCount == 1) {
                    throw HealthConnectWindowTimeoutException(
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(3600),
                        RuntimeException("timeout"),
                    )
                }
                emptyList()
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 10, onProgress = null)

            // The checkpoint saved upon timeout must have cleared page tokens
            val timeoutCheckpoint = savedCheckpoints.first { it.chunkDaysOverride != null }
            assertNull(timeoutCheckpoint.hrPageToken)
            assertNull(timeoutCheckpoint.hrvPageToken)
            assertEquals(5, timeoutCheckpoint.chunkDaysOverride)
        }

    @Test
    fun `chunk completion clears active page tokens and advances to next phase`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            // After entire resync completes, checkpoint is cleared
            assertNull(checkpointStore.value)
        }

    @Test
    fun `HealthIngestionCoordinator notifies onTokenUpdated per streamed page`() =
        runTest {
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore)
            val windowStart = Instant.parse("2024-06-01T00:00:00Z")
            val windowEnd = Instant.parse("2024-06-02T00:00:00Z")

            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), "hr-token-1")
                callback(listOf(mockk(relaxed = true)), null)
            }
            coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHrvRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), "hrv-token-1")
                callback(listOf(mockk(relaxed = true)), null)
            }

            val tokenEvents = mutableListOf<Pair<String?, String?>>()
            coordinator.ingestWindow(
                windowStart = windowStart,
                windowEnd = windowEnd,
                prefs = UserPreferences(),
                onTokenUpdated = { hrToken, hrvToken ->
                    tokenEvents.add(hrToken to hrvToken)
                },
            )

            assertEquals(
                listOf(
                    "hr-token-1" to null,
                    null to null,
                    null to "hrv-token-1",
                    null to null,
                ),
                tokenEvents,
            )
        }
}
