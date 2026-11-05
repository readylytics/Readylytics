package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ExerciseSessionRead
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.link.SessionLinker
import app.readylytics.health.core.model.domain.sync.link.SessionSpan
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pins the invariant that the number and alignment of ingestion windows must not change what ends
 * up persisted for heart-rate session tags: a sleep session that straddles a window boundary has to
 * yield the same `(recordType, sessionId)` for every HR sample whether it was ingested as one
 * window or as two windows split inside the session.
 *
 * The fake [HealthConnectRepository] deliberately returns a sleep session only from the window that
 * contains its start (the worst case for per-window tagging: the second window never sees the
 * session, so [HealthIngestionCoordinator] tags its in-session samples `RESTING` at ingest time).
 * Chunk independence is restored by the post-ingestion reconcile pass, which re-derives every tag
 * via [SessionLinker.resolve] over the complete persisted session list. [InMemoryIngestionStore]
 * models that pass with [InMemoryIngestionStore.reconcileSessionLinks] (the production
 * `SessionLinkReconcilerImpl` is Room-backed and covered in `core:database`; the existing resync
 * tests only mock `SessionLinkReconciler`, so it cannot run here).
 *
 * This is a characterization pin for refactors of [HealthIngestionCoordinator]; it passes on the
 * current code and must keep passing.
 */
class ChunkAlignmentIndependenceTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)

    private val sleepStart = Instant.parse("2024-06-01T22:00:00Z")
    private val sleepEnd = Instant.parse("2024-06-02T06:00:00Z")
    private val sleepRecord =
        DomainSleepSessionRecord(
            id = SLEEP_ID,
            startTime = sleepStart,
            endTime = sleepEnd,
            startZoneOffsetSeconds = 0,
            endZoneOffsetSeconds = 0,
            deviceName = "Watch",
            stages = emptyList(),
        )

    private val hrRecords =
        listOf(
            hrRecord("hr-1", "2024-06-01T20:00:00Z"),
            hrRecord("hr-2", "2024-06-01T22:30:00Z"),
            hrRecord("hr-3", "2024-06-01T23:59:00Z"),
            hrRecord("hr-4", "2024-06-02T00:30:00Z"),
            hrRecord("hr-5", "2024-06-02T05:00:00Z"),
            hrRecord("hr-6", "2024-06-02T07:00:00Z"),
        )

    @Before
    fun setup() {
        coEvery { hcRepo.readSleepSessions(any(), any(), any()) } coAnswers {
            val from = firstArg<Instant>()
            val to = secondArg<Instant>()
            ReadOutcome.Available(listOf(sleepRecord).filter { it.startTime >= from && it.startTime < to })
        }
        coEvery { hcRepo.readExerciseSessionsWithCompletion(any(), any(), any()) } returns
            ExerciseSessionRead(ReadOutcome.Available(emptyList()))
        coEvery { hcRepo.readWeightRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readVo2MaxRecords(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.hasVo2MaxPermission() } returns false
        coEvery { hcRepo.readStepsRecordsPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any(), any()) } coAnswers {
            val from = firstArg<Instant>()
            val to = secondArg<Instant>()
            val onPage = invocation.args[4] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
            onPage(hrRecords.filter { it.startTime >= from && it.startTime < to }, null)
            ReadOutcome.Available(Unit)
        }
    }

    @Test
    fun `chunkAlignment doesNotChangePersistedHrSessionTags`() =
        runTest {
            val oneWindow = ingest(WINDOW_START to WINDOW_END)
            val twoWindows = ingest(WINDOW_START to SPLIT_INSIDE_SESSION, SPLIT_INSIDE_SESSION to WINDOW_END)

            // Both alignments persist the same HR samples and the same session row ...
            assertEquals(oneWindow.heartRate.keys, twoWindows.heartRate.keys)
            assertEquals(hrRecords.size, oneWindow.heartRate.size)
            assertEquals(oneWindow.sleepSessions, twoWindows.sleepSessions)

            // ... and, once the reconcile pass has run, identical (recordType, sessionId) per sample.
            oneWindow.reconcileSessionLinks()
            twoWindows.reconcileSessionLinks()
            assertEquals(tagsOf(oneWindow), tagsOf(twoWindows))

            // Sanity: the shared answer is the correct one, so the equality is not vacuous.
            val sleep = RecordType.SLEEP.name to SLEEP_ID
            val resting = RecordType.RESTING.name to null
            assertEquals(
                mapOf(
                    "hr-1_${ms("2024-06-01T20:00:00Z")}" to resting,
                    "hr-2_${ms("2024-06-01T22:30:00Z")}" to sleep,
                    "hr-3_${ms("2024-06-01T23:59:00Z")}" to sleep,
                    "hr-4_${ms("2024-06-02T00:30:00Z")}" to sleep,
                    "hr-5_${ms("2024-06-02T05:00:00Z")}" to sleep,
                    "hr-6_${ms("2024-06-02T07:00:00Z")}" to resting,
                ),
                tagsOf(twoWindows),
            )
        }

    @Test
    fun `chunkAlignment splitting inside session still ingests the session exactly once`() =
        runTest {
            val twoWindows = ingest(WINDOW_START to SPLIT_INSIDE_SESSION, SPLIT_INSIDE_SESSION to WINDOW_END)

            assertEquals(setOf(SLEEP_ID), twoWindows.sleepSessions.keys)
        }

    private suspend fun ingest(vararg windows: Pair<Instant, Instant>): InMemoryIngestionStore {
        val store = InMemoryIngestionStore()
        val coordinator = HealthIngestionCoordinator(hcRepo, store, FakeScanStagingStore())
        windows.forEach { (start, end) ->
            coordinator.ingestWindow(windowStart = start, windowEnd = end, prefs = UserPreferences())
        }
        return store
    }

    private fun tagsOf(store: InMemoryIngestionStore): Map<String, Pair<String, String?>> =
        store.heartRate.mapValues { (_, row) -> row.recordType to row.sessionId }

    private fun hrRecord(
        id: String,
        time: String,
    ): DomainHeartRateRecord {
        val at = Instant.parse(time)
        return DomainHeartRateRecord(
            id = id,
            deviceName = "Watch",
            samples = listOf(DomainHeartRateSample(at, 60)),
            startTime = at,
            endTime = at.plusSeconds(1),
        )
    }

    private fun ms(time: String): Long = Instant.parse(time).toEpochMilli()

    /** Persists what the coordinator hands it; models `SessionLinkReconciler` over that state. */
    private class InMemoryIngestionStore : HealthIngestionStore {
        val heartRate = mutableMapOf<String, HeartRateInput>()
        val sleepSessions = mutableMapOf<String, SleepSessionInput>()

        override suspend fun persist(batch: HealthIngestionBatch) {
            batch.sleepSessions.forEach { sleepSessions[it.id] = it }
        }

        override suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
            sources.forEach { source -> source.rows.forEach { heartRate[it.id] = it } }
        }

        /** Same rule as the production reconcile: re-derive every tag over the complete session list. */
        fun reconcileSessionLinks() {
            val sleepSpans = sleepSessions.values.map { SessionSpan(it.id, it.startTime, it.endTime) }
            heartRate.replaceAll { _, row ->
                val link = SessionLinker.resolve(row.timestampMs, sleepSpans, emptyList())
                row.copy(recordType = link.recordType, sessionId = link.sessionId)
            }
        }

        override suspend fun replaceHrvSources(sources: List<SourcePayload<HrvInput>>) = Unit

        override suspend fun clearFrozenBaselines(
            start: LocalDate,
            endExclusive: LocalDate,
            zoneId: ZoneId,
        ) = Unit

        override suspend fun countHeartRateInRange(
            startMs: Long,
            endMs: Long,
        ): Int = heartRate.size

        override suspend fun countHrvInRange(
            startMs: Long,
            endMs: Long,
        ): Int = 0

        override suspend fun countSleepSessionsInRange(
            startMs: Long,
            endMs: Long,
        ): Int = sleepSessions.size

        override suspend fun countWorkoutsInRange(
            startMs: Long,
            endMs: Long,
        ): Int = 0

        override suspend fun persistSingleWorkoutRoute(
            workoutId: String,
            routePoints: List<WorkoutRoutePoint>,
            routeState: String,
            totalDistanceMeters: Float?,
            avgSpeedKmh: Float?,
            elevationGainMeters: Float?,
        ) = Unit

        override suspend fun reconcileWindow(
            scan: CompleteTypeScan,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? = null
    }

    private companion object {
        const val SLEEP_ID = "sleep-1"
        val WINDOW_START: Instant = Instant.parse("2024-06-01T00:00:00Z")
        val WINDOW_END: Instant = Instant.parse("2024-06-03T00:00:00Z")
        val SPLIT_INSIDE_SESSION: Instant = Instant.parse("2024-06-02T00:00:00Z")
    }
}
