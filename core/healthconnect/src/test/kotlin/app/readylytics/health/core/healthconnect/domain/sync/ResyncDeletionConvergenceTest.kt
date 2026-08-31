package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * Tests verifying deletion convergence during full historical resync (Finding R2-HC-001).
 */
class ResyncDeletionConvergenceTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = InMemoryResyncCheckpointStore()
    private val transactionRunner = RecordingTransactionRunner()
    private val fakeStore = FakeReconcilingHealthIngestionStore()

    private val fixedClock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC"))

    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        fakeStore.clear()
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coEvery { changeSynchronizer.captureChangesTokens() } returns emptyMap()
        coEvery { changeSynchronizer.commitTokens(any()) } returns Unit
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any()) } returns
            WalkForwardTrimpContext(TreeMap(), TreeMap())
        coEvery { scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any()) } returns
            WalkForwardBaselineContext(emptyList())
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())

        useCase =
            ResyncRangeUseCase(
                settingsRepo = settingsRepo,
                clock = fixedClock,
                sessionLinkReconciler = sessionLinkReconciler,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                healthIngestionStore = fakeStore,
                ingestion =
                    ResyncIngestionDependencies(
                        ingestionCoordinator = HealthIngestionCoordinator(hcRepo, fakeStore),
                        stepCountFetcher = StepCountFetcher(hcRepo),
                    ),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun `HC deletion converges - sleep session deleted in HC is deleted in Room during resync`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            val sessionDeleted =
                SleepSessionInput(
                    id = "session-deleted",
                    startTime = Instant.parse("2026-06-02T22:00:00Z").toEpochMilli(),
                    endTime = Instant.parse("2026-06-03T06:00:00Z").toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 60,
                    sleepScore = 85f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                )
            val sessionKept =
                SleepSessionInput(
                    id = "session-kept",
                    startTime = Instant.parse("2026-06-03T22:00:00Z").toEpochMilli(),
                    endTime = Instant.parse("2026-06-04T06:00:00Z").toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 60,
                    sleepScore = 85f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                )

            // Prepopulate Room with both sessions
            fakeStore.sleepSessions[sessionDeleted.id] = sessionDeleted
            fakeStore.sleepSessions[sessionKept.id] = sessionKept

            // Health Connect only returns sessionKept
            coEvery { hcRepo.readSleepSessions(any(), any()) } returns
                listOf(
                    DomainSleepSessionRecord(
                        id = sessionKept.id,
                        startTime = Instant.ofEpochMilli(sessionKept.startTime),
                        endTime = Instant.ofEpochMilli(sessionKept.endTime),
                        startZoneOffsetSeconds = 0,
                        endZoneOffsetSeconds = 0,
                        deviceName = sessionKept.deviceName ?: "Pixel Watch",
                        stages = emptyList(),
                    ),
                )

            val result = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertTrue("Resync must succeed", result.isSuccess)
            assertNull("Deleted session must no longer exist in Room", fakeStore.sleepSessions["session-deleted"])
            assertNotNull("Kept session must remain in Room", fakeStore.sleepSessions["session-kept"])
        }

    @Test
    fun `Unaffected data preserved - sleep session present in HC is preserved in Room during resync`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            val sessionKept =
                SleepSessionInput(
                    id = "session-kept",
                    startTime = Instant.parse("2026-06-03T22:00:00Z").toEpochMilli(),
                    endTime = Instant.parse("2026-06-04T06:00:00Z").toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 60,
                    sleepScore = 85f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                )

            fakeStore.sleepSessions[sessionKept.id] = sessionKept

            coEvery { hcRepo.readSleepSessions(any(), any()) } returns
                listOf(
                    DomainSleepSessionRecord(
                        id = sessionKept.id,
                        startTime = Instant.ofEpochMilli(sessionKept.startTime),
                        endTime = Instant.ofEpochMilli(sessionKept.endTime),
                        startZoneOffsetSeconds = 0,
                        endZoneOffsetSeconds = 0,
                        deviceName = sessionKept.deviceName ?: "Pixel Watch",
                        stages = emptyList(),
                    ),
                )

            val result = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertTrue("Resync must succeed", result.isSuccess)
            val preserved = fakeStore.sleepSessions["session-kept"]
            assertNotNull("Kept session must be preserved", preserved)
            assertEquals("session-kept", preserved?.id)
        }

    @Test
    fun `Out-of-window data untouched - records outside resync window are not deleted`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            // Sleep session far before the resync window (e.g. May 15)
            val outOfWindowSession =
                SleepSessionInput(
                    id = "session-out-of-window",
                    startTime = Instant.parse("2026-05-15T22:00:00Z").toEpochMilli(),
                    endTime = Instant.parse("2026-05-16T06:00:00Z").toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 60,
                    sleepScore = 85f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                )
            val inWindowSession =
                SleepSessionInput(
                    id = "session-in-window",
                    startTime = Instant.parse("2026-06-02T22:00:00Z").toEpochMilli(),
                    endTime = Instant.parse("2026-06-03T06:00:00Z").toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 60,
                    sleepScore = 85f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                )

            fakeStore.sleepSessions[outOfWindowSession.id] = outOfWindowSession
            fakeStore.sleepSessions[inWindowSession.id] = inWindowSession

            // Health Connect only returns in-window session
            coEvery { hcRepo.readSleepSessions(any(), any()) } returns
                listOf(
                    DomainSleepSessionRecord(
                        id = inWindowSession.id,
                        startTime = Instant.ofEpochMilli(inWindowSession.startTime),
                        endTime = Instant.ofEpochMilli(inWindowSession.endTime),
                        startZoneOffsetSeconds = 0,
                        endZoneOffsetSeconds = 0,
                        deviceName = inWindowSession.deviceName ?: "Pixel Watch",
                        stages = emptyList(),
                    ),
                )

            val result = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertTrue("Resync must succeed", result.isSuccess)
            assertNotNull("Out-of-window session must NOT be touched", fakeStore.sleepSessions["session-out-of-window"])
            assertNotNull("In-window session must remain", fakeStore.sleepSessions["session-in-window"])
        }

    @Test
    fun `Resync with no deletions is idempotent - running resync twice produces identical DB state`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            val session =
                DomainSleepSessionRecord(
                    id = "session-1",
                    startTime = Instant.parse("2026-06-02T22:00:00Z"),
                    endTime = Instant.parse("2026-06-03T06:00:00Z"),
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                    stages = emptyList(),
                )
            val workout =
                DomainExerciseSessionRecord(
                    id = "workout-1",
                    startTime = Instant.parse("2026-06-03T10:00:00Z"),
                    endTime = Instant.parse("2026-06-03T11:00:00Z"),
                    exerciseType = "RUNNING",
                    deviceName = "Pixel Watch",
                )

            coEvery { hcRepo.readSleepSessions(any(), any()) } returns listOf(session)
            coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns listOf(workout)

            // Pass 1
            val result1 = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertTrue("Pass 1 must succeed", result1.isSuccess)
            val sessionsAfterPass1 = HashMap(fakeStore.sleepSessions)
            val workoutsAfterPass1 = HashMap(fakeStore.workouts)

            // Pass 2
            val result2 = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertTrue("Pass 2 must succeed", result2.isSuccess)
            val sessionsAfterPass2 = HashMap(fakeStore.sleepSessions)
            val workoutsAfterPass2 = HashMap(fakeStore.workouts)

            assertEquals(
                "Sleep sessions must be identical across resync passes",
                sessionsAfterPass1,
                sessionsAfterPass2,
            )
            assertEquals(
                "Workouts must be identical across resync passes",
                workoutsAfterPass1,
                workoutsAfterPass2,
            )
        }

    @Test
    fun `Recompute-only mode does NOT trigger deletion reconciliation`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            val session =
                SleepSessionInput(
                    id = "session-to-keep-on-recompute",
                    startTime = Instant.parse("2026-06-02T22:00:00Z").toEpochMilli(),
                    endTime = Instant.parse("2026-06-03T06:00:00Z").toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 60,
                    sleepScore = 85f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel Watch",
                )
            fakeStore.sleepSessions[session.id] = session

            // HC returns nothing, but skipIngestAndPrune is true
            coEvery { hcRepo.readSleepSessions(any(), any()) } returns emptyList()

            val result =
                useCase.run(
                    startDate = startDate,
                    endDate = endDate,
                    chunkDays = 30,
                    onProgress = null,
                    skipIngestAndPrune = true,
                )

            assertTrue("Resync must succeed", result.isSuccess)
            val preserved = fakeStore.sleepSessions[session.id]
            assertNotNull("Session must not be deleted when skipIngestAndPrune is true", preserved)
        }

    private class FakeReconcilingHealthIngestionStore : HealthIngestionStore {
        val sleepSessions = mutableMapOf<String, SleepSessionInput>()
        val workouts = mutableMapOf<String, WorkoutInput>()

        fun clear() {
            sleepSessions.clear()
            workouts.clear()
        }

        override suspend fun persist(batch: HealthIngestionBatch) {
            batch.sleepSessions.forEach { sleepSessions[it.id] = it }
            batch.workouts.forEach { workouts[it.id] = it }
        }

        override suspend fun persistHeartRateSamples(samples: List<HeartRateInput>) = Unit
        override suspend fun persistHrvSamples(samples: List<HrvInput>) = Unit
        override suspend fun clearFrozenBaselines(start: LocalDate, endExclusive: LocalDate, zoneId: ZoneId) = Unit
        override suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int = 0
        override suspend fun countHrvInRange(startMs: Long, endMs: Long): Int = 0
        override suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int = sleepSessions.size
        override suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int = workouts.size

        override suspend fun persistSingleWorkoutRoute(
            workoutId: String,
            routePoints: List<WorkoutRoutePoint>,
            routeState: String,
            totalDistanceMeters: Float?,
            avgSpeedKmh: Float?,
            elevationGainMeters: Float?,
        ) = Unit

        override suspend fun reconcileWindow(
            type: HealthDataType,
            windowStartMs: Long,
            windowEndMs: Long,
            hcIds: Set<String>,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? =
            when (type) {
                HealthDataType.SLEEP ->
                    reconcileItems(
                        map = sleepSessions,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        hcIds = hcIds,
                        zoneId = zoneId,
                        getStart = { it.startTime },
                        getEnd = { it.endTime },
                    )
                HealthDataType.EXERCISE ->
                    reconcileItems(
                        map = workouts,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        hcIds = hcIds,
                        zoneId = zoneId,
                        getStart = { it.startTime },
                        getEnd = { it.endTime },
                    )
                else -> null
            }

        private fun <T> reconcileItems(
            map: MutableMap<String, T>,
            windowStartMs: Long,
            windowEndMs: Long,
            hcIds: Set<String>,
            zoneId: ZoneId,
            getStart: (T) -> Long,
            getEnd: (T) -> Long,
        ): ScoreInvalidation.AffectedRange? {
            val inRange = map.entries.filter { getStart(it.value) >= windowStartMs && getEnd(it.value) <= windowEndMs }
            val toDelete = inRange.filter { it.key !in hcIds }
            if (toDelete.isEmpty()) return null
            toDelete.forEach { map.remove(it.key) }
            val startDay = Instant.ofEpochMilli(toDelete.minOf { getStart(it.value) }).atZone(zoneId).toLocalDate()
            val endDay = Instant.ofEpochMilli(toDelete.maxOf { getEnd(it.value) }).atZone(zoneId).toLocalDate()
            return ScoreInvalidation.AffectedRange(start = startDay, endInclusive = endDay)
        }
    }
}
