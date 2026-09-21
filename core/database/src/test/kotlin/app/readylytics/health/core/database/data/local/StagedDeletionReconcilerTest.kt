package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class StagedDeletionReconcilerTest {
    private lateinit var database: HealthDatabase
    private lateinit var daos: HealthRecordDaos
    private lateinit var scanStagingDao: ScanStagingDao
    private val scanId = ScanIdentity(runId = "run-a", chunkId = "0")
    private val windowStartMs = 0L
    private val windowEndMs = 10 * 86_400_000L

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        scanStagingDao = database.scanStagingDao()
        daos =
            HealthRecordDaos(
                sleepSessionDao = database.sleepSessionDao(),
                sleepStageDao = database.sleepStageDao(),
                heartRateDao = database.heartRateDao(),
                hrvDao = database.hrvDao(),
                workoutDao = database.workoutDao(),
                workoutRoutePointDao = database.workoutRoutePointDao(),
                weightRecordDao = database.weightRecordDao(),
                bodyFatRecordDao = database.bodyFatRecordDao(),
                bloodPressureRecordDao = database.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
                stepRecordDao = database.stepRecordDao(),
                sourceRecordDao = database.sourceRecordDao(),
                minuteBucketMaintenanceDao = database.minuteBucketMaintenanceDao(),
            )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deletesOnlySessionsAbsentFromACompleteScan() =
        runBlocking {
            database.sleepSessionDao().upsertAll(
                listOf(session("s1", 1_000L, 2_000L), session("s2", 3_000L, 4_000L)),
            )
            stage(HealthDataType.SLEEP, listOf("s1"), complete = true)

            val affected = reconcile(HealthDataType.SLEEP)

            assertEquals(listOf("s1"), database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).map { it.id })
            assertNotNull(affected)
            assertEquals(java.time.LocalDate.of(1970, 1, 1), affected?.start)
        }

    @Test
    fun refusesToDeleteFromAnIncompleteScan() =
        runBlocking {
            database.sleepSessionDao().upsertAll(
                listOf(session("s1", 1_000L, 2_000L), session("s2", 3_000L, 4_000L)),
            )
            stage(HealthDataType.SLEEP, listOf("s1"), complete = false)

            val affected = reconcile(HealthDataType.SLEEP)

            assertEquals(2, database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).size)
            assertNull(affected)
        }

    @Test
    fun emptyCompleteScanDeletesEveryLocalRowInWindow() =
        runBlocking {
            database.sleepSessionDao().upsertAll(listOf(session("s1", 1_000L, 2_000L)))
            stage(HealthDataType.SLEEP, emptyList(), complete = true)

            val affected = reconcile(HealthDataType.SLEEP)

            assertEquals(0, database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).size)
            assertNotNull(affected)
        }

    @Test
    fun deletionScopeIsIndependentOfStagedCardinality() =
        runBlocking {
            // 2_000 staged ids is far past SQLITE_MAX_VARIABLE_NUMBER; the old NOT IN (:ids) path
            // could not express this at all.
            val sessions = (1..2_000).map { session("s$it", it * 10_000L, it * 10_000L + 1_000L) }
            database.sleepSessionDao().upsertAll(sessions)
            database.sleepSessionDao().upsertAll(listOf(session("gone", 5L, 100L)))
            stage(HealthDataType.SLEEP, sessions.map { it.id }, complete = true)

            reconcile(HealthDataType.SLEEP)

            val remaining = database.sleepSessionDao().getBetween(windowStartMs, windowEndMs).map { it.id }
            assertEquals(2_000, remaining.size)
            assertEquals(false, remaining.contains("gone"))
        }

    private suspend fun reconcile(type: HealthDataType) =
        StagedDeletionReconciler.reconcile(
            daos = daos,
            vo2MaxRecordDao = database.vo2MaxRecordDao(),
            scanStagingDao = scanStagingDao,
            scan =
                CompleteTypeScan(
                    type = type,
                    windowStartMs = windowStartMs,
                    windowEndExclusiveMs = windowEndMs,
                    sourceSelectionId = "",
                    scan = scanId,
                ),
            zoneId = ZoneOffset.UTC,
        )

    private suspend fun stage(
        type: HealthDataType,
        ids: List<String>,
        complete: Boolean,
    ) {
        scanStagingDao.insertSeenIds(ids.map { ScanSeenIdEntity(scanId.runId, scanId.chunkId, type.name, it) })
        scanStagingDao.upsertState(
            ScanTypeStateEntity(
                runId = scanId.runId,
                chunkId = scanId.chunkId,
                recordType = type.name,
                state = if (complete) ScanStagingDao.STATE_COMPLETE else ScanStagingDao.STATE_SCANNING,
                stagedCount = ids.size,
                updatedAtMs = 0L,
            ),
        )
    }

    private fun session(
        id: String,
        startTime: Long,
        endTime: Long,
    ) = SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = 1,
        efficiency = 1f,
        deepSleepMinutes = 0,
        remSleepMinutes = 0,
        lightSleepMinutes = 1,
        awakeMinutes = 0,
        sleepScore = null,
        startZoneOffsetSeconds = null,
        endZoneOffsetSeconds = null,
        deviceName = null,
    )
}
