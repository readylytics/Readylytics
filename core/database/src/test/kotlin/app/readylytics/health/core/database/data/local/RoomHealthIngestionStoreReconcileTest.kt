package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
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
        )

    private val zoneId = ZoneId.of("UTC")

    @Before
    fun setup() {
        clearMocks(sleepSessionDao, sleepStageDao, sourceRecordDao, heartRateDao, dailySummaryDao)
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
            val session2 =
                SleepSessionEntity(
                    id = "s2",
                    startTime = startMs + 30000000,
                    endTime = startMs + 40000000,
                    durationMinutes = 166,
                    efficiency = 0.8f,
                    deepSleepMinutes = 20,
                    remSleepMinutes = 30,
                    lightSleepMinutes = 100,
                    awakeMinutes = 16,
                    sleepScore = 70f,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Watch",
                )

            coEvery { daos.sleepSessionDao.getBetween(startMs, endMs) } returns listOf(session1, session2)

            // Only s2 is in HC; s1 was deleted
            val scan = CompleteTypeScan(HealthDataType.SLEEP, startMs, endMs, "", setOf("s2"))
            val affected = store.reconcileWindow(scan, zoneId)

            coVerify { daos.sleepStageDao.deleteForSessions(listOf("s1")) }
            coVerify { daos.sleepSessionDao.deleteSessionsNotIn(startMs, endMs, listOf("s2")) }

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

            coEvery { daos.sleepSessionDao.getBetween(startMs, endMs) } returns listOf(session1)

            val scan = CompleteTypeScan(HealthDataType.SLEEP, startMs, endMs, "", setOf("s1"))
            val affected = store.reconcileWindow(scan, zoneId)

            assertNull(affected)
            coVerify(exactly = 0) { daos.sleepSessionDao.deleteSessionsNotIn(any(), any(), any()) }
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

            coEvery {
                daos.sourceRecordDao.getAuthoritativeSourcesOverlapping("HEART_RATE", startMs, endMs)
            } returns listOf(src1)

            // hc-src-1 is deleted in HC
            val scan = CompleteTypeScan(HealthDataType.HEART_RATE, startMs, endMs, "", emptySet())
            val affected = store.reconcileWindow(scan, zoneId)

            coVerify { daos.heartRateDao.deleteBySourceRecordRef(101L) }
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

            coEvery {
                daos.sourceRecordDao.getAuthoritativeSourcesOverlapping("HEART_RATE", startMs, endMs)
            } returns listOf(srcSpanning)

            val scan = CompleteTypeScan(HealthDataType.HEART_RATE, startMs, endMs, "", emptySet())
            val affected = store.reconcileWindow(scan, zoneId)

            coVerify { daos.heartRateDao.deleteBySourceRecordRef(102L) }
            coVerify { daos.sourceRecordDao.deleteBySourceRecordId("hc-src-spanning") }
            assertEquals(LocalDate.of(2023, 11, 13), affected?.start)
            assertEquals(LocalDate.of(2023, 11, 16), affected?.endInclusive)
        }

    @Test
    fun `reconcileWindow for HEART_RATE delegates only to getAuthoritativeSourcesOverlapping`() =
        runTest {
            val startMs = 1700000000000L
            val endMs = 1700086400000L

            coEvery {
                daos.sourceRecordDao.getAuthoritativeSourcesOverlapping("HEART_RATE", startMs, endMs)
            } returns emptyList()

            val scan = CompleteTypeScan(HealthDataType.HEART_RATE, startMs, endMs, "", emptySet())
            val affected = store.reconcileWindow(scan, zoneId)

            assertNull(affected)
            coVerify(exactly = 1) {
                daos.sourceRecordDao.getAuthoritativeSourcesOverlapping("HEART_RATE", startMs, endMs)
            }
            coVerify(exactly = 0) {
                daos.heartRateDao.deleteBySourceRecordRef(any())
            }
        }
}
