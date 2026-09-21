package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.StagedDeletionBounds
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RoomHealthIngestionStoreReconcileTest {
    private val transactionRunner =
        object : TransactionRunner {
            override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
        }

    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val sleepStageDao = mockk<SleepStageDao>(relaxed = true)
    private val sourceRecordDao = mockk<SourceRecordDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val scanStagingDao = mockk<ScanStagingDao>(relaxed = true)

    private val daos =
        HealthRecordDaos(
            sleepSessionDao = sleepSessionDao,
            sleepStageDao = sleepStageDao,
            heartRateDao = heartRateDao,
            hrvDao = mockk(relaxed = true),
            workoutDao = mockk(relaxed = true),
            workoutRoutePointDao = mockk(relaxed = true),
            weightRecordDao = mockk(relaxed = true),
            bodyFatRecordDao = mockk(relaxed = true),
            bloodPressureRecordDao = mockk(relaxed = true),
            oxygenSaturationRecordDao = mockk(relaxed = true),
            bodyTemperatureRecordDao = mockk(relaxed = true),
            stepRecordDao = mockk(relaxed = true),
            sourceRecordDao = sourceRecordDao,
            minuteBucketMaintenanceDao = mockk(relaxed = true),
        )

    private val store =
        RoomHealthIngestionStore(
            daos = daos,
            dailySummaryDao = dailySummaryDao,
            transactionRunner = transactionRunner,
            vo2MaxRecordDao = mockk(relaxed = true),
            scanStagingDao = scanStagingDao,
        )

    private val zoneId = ZoneId.of("UTC")

    @Before
    fun setup() {
        clearMocks(sleepSessionDao, sleepStageDao, sourceRecordDao, heartRateDao, dailySummaryDao, scanStagingDao)
    }

    @Test
    fun `reconcileWindow for SLEEP deletes missing sessions and stages and returns affected range`() =
        runTest {
            val startMs = 1700000000000L
            val endMs = 1700086400000L

            val session1 =
                SleepSessionEntity(
                    id = "s1",
                    startTime = startMs + 1000,
                    endTime = startMs + 28800000,
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 60,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 60,
                    sleepScore = 80f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Watch",
                )

            val scanId = ScanIdentity("run-1", "0")
            stubScanState(scanId, HealthDataType.SLEEP, complete = true)

            coEvery {
                daos.sleepSessionDao.boundsOfUnstagedSessions(startMs, endMs, scanId.runId, scanId.chunkId, "SLEEP")
            } returns StagedDeletionBounds(session1.startTime, session1.endTime)

            val scan = CompleteTypeScan(HealthDataType.SLEEP, startMs, endMs, "", scanId)
            val affected = store.reconcileWindow(scan, zoneId)

            coVerify {
                daos.sleepSessionDao.deleteStagesOfUnstagedSessions(
                    startMs,
                    endMs,
                    scanId.runId,
                    scanId.chunkId,
                    "SLEEP",
                )
            }
            coVerify {
                daos.sleepSessionDao.deleteSessionsNotStaged(
                    startMs,
                    endMs,
                    scanId.runId,
                    scanId.chunkId,
                    "SLEEP",
                )
            }

            val expectedRange =
                ScoreInvalidation.AffectedRange(
                    start = LocalDate.of(2023, 11, 14),
                    endInclusive = LocalDate.of(2023, 11, 15),
                )
            assertEquals(expectedRange, affected)
        }

    @Test
    fun `reconcileWindow returns null when no records need deletion`() =
        runTest {
            val startMs = 1700000000000L
            val endMs = 1700086400000L

            val scanId = ScanIdentity("run-1", "0")
            stubScanState(scanId, HealthDataType.SLEEP, complete = true)

            coEvery {
                daos.sleepSessionDao.boundsOfUnstagedSessions(startMs, endMs, scanId.runId, scanId.chunkId, "SLEEP")
            } returns StagedDeletionBounds(null, null)

            val scan = CompleteTypeScan(HealthDataType.SLEEP, startMs, endMs, "", scanId)
            val affected = store.reconcileWindow(scan, zoneId)

            assertNull(affected)
            coVerify(exactly = 0) { daos.sleepSessionDao.deleteSessionsNotStaged(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `reconcileWindow for HEART_RATE deletes child samples and source records`() =
        runTest {
            val startMs = 1700000000000L
            val endMs = 1700086400000L

            val src1 =
                HealthSourceRecordEntity(
                    id = 101L,
                    sourceRecordId = "hc-src-1",
                    recordType = "HEART_RATE",
                    createdAtMs = startMs + 5000,
                    recordStartMs = startMs + 1000,
                    recordEndExclusiveMs = startMs + 2000,
                    metadataState = "AUTHORITATIVE",
                )

            val scanId = ScanIdentity("run-1", "0")
            stubScanState(scanId, HealthDataType.HEART_RATE, complete = true)

            coEvery {
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = "HEART_RATE",
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    runId = scanId.runId,
                    chunkId = scanId.chunkId,
                    afterRef = Long.MIN_VALUE,
                    limit = 500,
                )
            } returns listOf(src1)

            coEvery {
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = "HEART_RATE",
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    runId = scanId.runId,
                    chunkId = scanId.chunkId,
                    afterRef = 101L,
                    limit = 500,
                )
            } returns emptyList()

            // hc-src-1 is deleted in HC
            val scan = CompleteTypeScan(HealthDataType.HEART_RATE, startMs, endMs, "", scanId)
            val affected = store.reconcileWindow(scan, zoneId)

            coVerify { daos.sourceRecordDao.deleteBySourceRecordId("hc-src-1") }
            assertEquals(LocalDate.of(2023, 11, 14), affected?.start)
        }

    @Test
    fun `reconcileWindow for HEART_RATE expands affected range to authoritative bounds spanning window boundary`() =
        runTest {
            val startMs = 1700000000000L // 2023-11-14 22:13:20 UTC
            val endMs = 1700086400000L // 2023-11-15 22:13:20 UTC

            // Record starts on previous day (e.g. 2023-11-13) and ends on 2023-11-16
            val recordStart = startMs - 86400000L // 2023-11-13
            val recordEnd = endMs + 86400000L // 2023-11-16

            val srcSpanning =
                HealthSourceRecordEntity(
                    id = 102L,
                    sourceRecordId = "hc-src-spanning",
                    recordType = "HEART_RATE",
                    createdAtMs = startMs,
                    recordStartMs = recordStart,
                    recordEndExclusiveMs = recordEnd,
                    metadataState = "AUTHORITATIVE",
                )

            val scanId = ScanIdentity("run-1", "0")
            stubScanState(scanId, HealthDataType.HEART_RATE, complete = true)

            coEvery {
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = "HEART_RATE",
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    runId = scanId.runId,
                    chunkId = scanId.chunkId,
                    afterRef = Long.MIN_VALUE,
                    limit = 500,
                )
            } returns listOf(srcSpanning)

            coEvery {
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = "HEART_RATE",
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    runId = scanId.runId,
                    chunkId = scanId.chunkId,
                    afterRef = 102L,
                    limit = 500,
                )
            } returns emptyList()

            val scan = CompleteTypeScan(HealthDataType.HEART_RATE, startMs, endMs, "", scanId)
            val affected = store.reconcileWindow(scan, zoneId)

            coVerify { daos.sourceRecordDao.deleteBySourceRecordId("hc-src-spanning") }
            assertEquals(LocalDate.of(2023, 11, 13), affected?.start)
            assertEquals(LocalDate.of(2023, 11, 16), affected?.endInclusive)
        }

    @Test
    fun `reconcileWindow for HEART_RATE delegates only to pageUnstagedAuthoritativeSources`() =
        runTest {
            val startMs = 1700000000000L
            val endMs = 1700086400000L

            val scanId = ScanIdentity("run-1", "0")
            stubScanState(scanId, HealthDataType.HEART_RATE, complete = true)

            coEvery {
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = "HEART_RATE",
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    runId = scanId.runId,
                    chunkId = scanId.chunkId,
                    afterRef = Long.MIN_VALUE,
                    limit = 500,
                )
            } returns emptyList()

            val scan = CompleteTypeScan(HealthDataType.HEART_RATE, startMs, endMs, "", scanId)
            val affected = store.reconcileWindow(scan, zoneId)

            assertNull(affected)
            coVerify(exactly = 1) {
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = "HEART_RATE",
                    windowStartMs = startMs,
                    windowEndMs = endMs,
                    runId = scanId.runId,
                    chunkId = scanId.chunkId,
                    afterRef = Long.MIN_VALUE,
                    limit = 500,
                )
            }
            coVerify(exactly = 0) {
                daos.sourceRecordDao.deleteBySourceRecordId(any())
            }
        }

    private fun stubScanState(scanId: ScanIdentity, type: HealthDataType, complete: Boolean = true) {
        coEvery {
            scanStagingDao.getState(scanId.runId, scanId.chunkId, type.name)
        } returns
            ScanTypeStateEntity(
                runId = scanId.runId,
                chunkId = scanId.chunkId,
                recordType = type.name,
                state = if (complete) ScanStagingDao.STATE_COMPLETE else ScanStagingDao.STATE_SCANNING,
                stagedCount = 0,
                updatedAtMs = 0L,
            )
    }
}
