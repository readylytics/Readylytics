package app.readylytics.health.domain.sync.link

import app.readylytics.health.data.healthconnect.WorkoutMapper
import app.readylytics.health.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.domain.model.RecordType
import app.readylytics.health.domain.repository.TransactionRunner
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SessionLinkReconcilerTest {
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)

    private lateinit var reconciler: SessionLinkReconciler

    private val sleepSession =
        SleepSessionEntity(
            id = "sleep_1",
            startTime = 1_000L,
            endTime = 5_000L,
            durationMinutes = 0,
            efficiency = 0f,
            deepSleepMinutes = 0,
            remSleepMinutes = 0,
            lightSleepMinutes = 0,
            awakeMinutes = 0,
        )

    private val workoutSession =
        WorkoutRecordEntity(
            id = "workout_1",
            startTime = 10_000L,
            endTime = 14_000L,
            exerciseType = "RUNNING",
            durationMinutes = 0,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 0f,
            avgHr = 0f,
        )

    @Before
    fun setup() {
        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns listOf(sleepSession)
        coEvery { workoutDao.getOverlapping(any(), any()) } returns listOf(workoutSession)
        coEvery { workoutDao.getById("workout_1") } returns workoutSession

        reconciler = SessionLinkReconcilerImpl(sleepSessionDao, workoutDao, heartRateDao, hrvDao, transactionRunner)
    }

    @Test
    fun `relinks heart rate samples split across chunk boundaries to the correct session`() =
        runTest {
            val hr1 =
                HeartRateRecordEntity(
                    id = "hr1",
                    timestampMs = 1_500L,
                    beatsPerMinute = 50,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                )
            val hr2 =
                HeartRateRecordEntity(
                    id = "hr2",
                    timestampMs = 4_500L,
                    beatsPerMinute = 48,
                    recordType = RecordType.SLEEP.name,
                    sessionId = "sleep_1",
                )
            val hr3 =
                HeartRateRecordEntity(
                    id = "hr3",
                    timestampMs = 11_000L,
                    beatsPerMinute = 120,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                )

            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 0L, "", any()) } returns listOf(hr1, hr2, hr3)
            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 11_000L, "hr3", any()) } returns emptyList()
            coEvery { heartRateDao.getByTimeRange(10_000L, 14_000L) } returns listOf(hr3)

            val upsertSlot = slot<List<HeartRateRecordEntity>>()
            coEvery { heartRateDao.upsertAll(capture(upsertSlot)) } returns Unit

            reconciler.reconcile(0L, 20_000L, WorkoutMapper.zoneThresholds())

            val updated = upsertSlot.captured.associateBy { it.id }
            assertEquals(RecordType.SLEEP.name, updated.getValue("hr1").recordType)
            assertEquals("sleep_1", updated.getValue("hr1").sessionId)
            assertEquals(RecordType.EXERCISE.name, updated.getValue("hr3").recordType)
            assertEquals("workout_1", updated.getValue("hr3").sessionId)
            org.junit.Assert.assertNull(updated["hr2"])
        }

    @Test
    fun `relinks hrv samples split across chunk boundaries to the correct session`() =
        runTest {
            val hrv1 =
                HrvRecordEntity(
                    id = "hrv1",
                    timestampMs = 2_000L,
                    rmssdMs = 42f,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                )

            coEvery { hrvDao.getKeysetPage(0L, 20_000L, 0L, "", any()) } returns listOf(hrv1)
            coEvery { hrvDao.getKeysetPage(0L, 20_000L, 2_000L, "hrv1", any()) } returns emptyList()
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()

            val upsertSlot = slot<List<HrvRecordEntity>>()
            coEvery { hrvDao.upsertAll(capture(upsertSlot)) } returns Unit

            reconciler.reconcile(0L, 20_000L, WorkoutMapper.zoneThresholds())

            val updated = upsertSlot.captured.associateBy { it.id }
            assertEquals(RecordType.SLEEP.name, updated.getValue("hrv1").recordType)
            assertEquals("sleep_1", updated.getValue("hrv1").sessionId)
        }

    @Test
    fun `recomputes workout metrics from the full set of heart rate samples in its window`() =
        runTest {
            val hr3 =
                HeartRateRecordEntity(
                    id = "hr3",
                    timestampMs = 11_000L,
                    beatsPerMinute = 120,
                    recordType = RecordType.EXERCISE.name,
                    sessionId = "workout_1",
                )

            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 0L, "", any()) } returns listOf(hr3)
            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 11_000L, "hr3", any()) } returns emptyList()
            coEvery { heartRateDao.getByTimeRange(10_000L, 14_000L) } returns listOf(hr3)
            coEvery { hrvDao.getKeysetPage(any(), any(), any(), any(), any()) } returns emptyList()

            val workoutUpsertSlot = slot<List<WorkoutRecordEntity>>()
            coEvery { workoutDao.upsertAll(capture(workoutUpsertSlot)) } returns Unit

            reconciler.reconcile(0L, 20_000L, WorkoutMapper.zoneThresholds())

            val expected = WorkoutMapper.computeMetrics(10_000L, 14_000L, listOf(hr3), WorkoutMapper.zoneThresholds())
            val updated = workoutUpsertSlot.captured.single { it.id == "workout_1" }
            assertEquals(expected.trimp, updated.trimp)
            assertEquals(expected.durationMinutes, updated.durationMinutes)
            assertEquals(expected.avgHr, updated.avgHr)
        }

    @Test
    fun `produces identical tagging for two differently chunk-split inputs of the same data`() =
        runTest {
            val hr1A =
                HeartRateRecordEntity(
                    id = "hr1",
                    timestampMs = 1_500L,
                    beatsPerMinute = 50,
                    recordType = RecordType.RESTING.name,
                    sessionId = null,
                )
            val hr2A =
                HeartRateRecordEntity(
                    id = "hr2",
                    timestampMs = 4_500L,
                    beatsPerMinute = 48,
                    recordType = RecordType.SLEEP.name,
                    sessionId = "sleep_1",
                )

            val hr1B = hr1A.copy(recordType = RecordType.SLEEP.name, sessionId = "sleep_1")
            val hr2B = hr2A

            coEvery { heartRateDao.getByTimeRange(10_000L, 14_000L) } returns emptyList()
            coEvery { hrvDao.getKeysetPage(any(), any(), any(), any(), any()) } returns emptyList()

            // --- Scenario A ---
            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 0L, "", any()) } returns listOf(hr1A, hr2A)
            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 4_500L, "hr2", any()) } returns emptyList()
            val upsertSlotA = slot<List<HeartRateRecordEntity>>()
            coEvery { heartRateDao.upsertAll(capture(upsertSlotA)) } returns Unit
            reconciler.reconcile(0L, 20_000L, WorkoutMapper.zoneThresholds())
            val upsertedA = if (upsertSlotA.isCaptured) upsertSlotA.captured else emptyList()
            val resultA =
                (listOf(hr1A, hr2A).associateBy { it.id } + upsertedA.associateBy { it.id })
                    .mapValues { it.value.recordType to it.value.sessionId }

            // --- Scenario B ---
            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 0L, "", any()) } returns listOf(hr1B, hr2B)
            coEvery { heartRateDao.getKeysetPage(0L, 20_000L, 4_500L, "hr2", any()) } returns emptyList()
            val upsertSlotB = slot<List<HeartRateRecordEntity>>()
            coEvery { heartRateDao.upsertAll(capture(upsertSlotB)) } returns Unit
            reconciler.reconcile(0L, 20_000L, WorkoutMapper.zoneThresholds())
            val upsertedB = if (upsertSlotB.isCaptured) upsertSlotB.captured else emptyList()
            val resultB =
                (listOf(hr1B, hr2B).associateBy { it.id } + upsertedB.associateBy { it.id })
                    .mapValues { it.value.recordType to it.value.sessionId }

            assertEquals(resultA, resultB)
        }
}
