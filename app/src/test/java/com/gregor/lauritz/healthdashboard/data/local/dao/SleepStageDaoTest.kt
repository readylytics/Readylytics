package com.gregor.lauritz.healthdashboard.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepStageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SleepStageDaoTest {
    private lateinit var database: HealthDatabase
    private lateinit var dao: SleepStageDao
    private lateinit var sessionDao: SleepSessionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        dao = database.sleepStageDao()
        sessionDao = database.sleepSessionDao()

        // Create test session
        val testSession =
            SleepSessionEntity(
                id = "session1",
                startTime = 0,
                endTime = 6_000_000,
                durationMinutes = 100,
                efficiency = 85f,
                deepSleepMinutes = 30,
                lightSleepMinutes = 40,
                remSleepMinutes = 20,
                awakeMinutes = 10,
                sleepScore = 85f,
                startZoneOffsetSeconds = null,
                endZoneOffsetSeconds = null,
                deviceName = "Test Device",
            )
        runTest { sessionDao.upsertAll(listOf(testSession)) }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun upsertAll_insertsAllStages() =
        runTest {
            // Given: 3 stages
            val stages =
                listOf(
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "DEEP",
                        startTime = 0,
                        endTime = 1_800_000,
                        durationMinutes = 30,
                    ),
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "LIGHT",
                        startTime = 1_800_000,
                        endTime = 4_200_000,
                        durationMinutes = 40,
                    ),
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "REM",
                        startTime = 4_200_000,
                        endTime = 5_400_000,
                        durationMinutes = 20,
                    ),
                )

            // When: upsert all stages
            dao.upsertAll(stages)

            // Then: all 3 stages are in DB
            val observed = dao.observeStagesForSession("session1")
            val result = mutableListOf<SleepStageEntity>()
            val job =
                kotlinx.coroutines.launch {
                    observed.collect { result.addAll(it) }
                }

            // Wait for collection
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(100)
                job.cancel()
            }

            assertEquals(3, result.size)
        }

    @Test
    fun observeStagesForSession_returnsOrderedByStartTime() =
        runTest {
            // Given: 3 stages out of order
            val stages =
                listOf(
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "REM",
                        startTime = 4_200_000,
                        endTime = 5_400_000,
                        durationMinutes = 20,
                    ),
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "DEEP",
                        startTime = 0,
                        endTime = 1_800_000,
                        durationMinutes = 30,
                    ),
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "LIGHT",
                        startTime = 1_800_000,
                        endTime = 4_200_000,
                        durationMinutes = 40,
                    ),
                )
            dao.upsertAll(stages)

            // When: observe stages
            val result = mutableListOf<SleepStageEntity>()
            val job =
                kotlinx.coroutines.launch {
                    dao.observeStagesForSession("session1").collect { result.addAll(it) }
                }

            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(100)
                job.cancel()
            }

            // Then: stages returned ordered by startTime
            assertEquals(3, result.size)
            assertEquals(0L, result[0].startTime) // DEEP first
            assertEquals(1_800_000L, result[1].startTime) // LIGHT second
            assertEquals(4_200_000L, result[2].startTime) // REM third
        }

    @Test
    fun deleteForSession_removesAllStagesForSession() =
        runTest {
            // Given: stages for 2 sessions
            val session1Stages =
                listOf(
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "DEEP",
                        startTime = 0,
                        endTime = 1_800_000,
                        durationMinutes = 30,
                    ),
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "LIGHT",
                        startTime = 1_800_000,
                        endTime = 4_200_000,
                        durationMinutes = 40,
                    ),
                )
            val session2Stages =
                listOf(
                    SleepStageEntity(
                        sessionId = "session2",
                        stageType = "DEEP",
                        startTime = 0,
                        endTime = 1_800_000,
                        durationMinutes = 30,
                    ),
                )
            dao.upsertAll(session1Stages + session2Stages)

            // When: delete stages for session1
            dao.deleteForSession("session1")

            // Then: only session2 stages remain
            val result = mutableListOf<SleepStageEntity>()
            val job =
                kotlinx.coroutines.launch {
                    dao.observeStagesForSession("session2").collect { result.addAll(it) }
                }

            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(100)
                job.cancel()
            }

            assertEquals(1, result.size)
            assertEquals("session2", result[0].sessionId)
        }

    @Test
    fun deleteForSession_returnsDeletedCount() =
        runTest {
            // Given: 3 stages for session1
            val stages =
                (1..3).map {
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "DEEP",
                        startTime = it * 100L,
                        endTime = (it + 1) * 100L,
                        durationMinutes = 10,
                    )
                }
            dao.upsertAll(stages)

            // When: delete stages for session1
            val deletedCount = dao.deleteForSession("session1")

            // Then: returned count is 3
            assertEquals(3, deletedCount)
        }

    @Test
    fun uniqueConstraint_preventsIdenticalStages() =
        runTest {
            // Given: stage with (sessionId="session1", startTime=0)
            val stage1 =
                SleepStageEntity(
                    sessionId = "session1",
                    stageType = "DEEP",
                    startTime = 0,
                    endTime = 1_800_000,
                    durationMinutes = 30,
                )
            dao.upsertAll(listOf(stage1))

            // When: try to insert identical stage
            val stage2 =
                SleepStageEntity(
                    sessionId = "session1",
                    stageType = "LIGHT", // Different type but same (sessionId, startTime)
                    startTime = 0, // ← Same startTime!
                    endTime = 1_800_000,
                    durationMinutes = 30,
                )

            // Then: should raise constraint violation
            var constraintViolated = false
            try {
                dao.upsertAll(listOf(stage2))
            } catch (e: Exception) {
                // SQLite will raise exception for unique constraint
                constraintViolated = true
            }

            assertTrue(constraintViolated, "Expected unique constraint violation")
        }

    @Test
    fun cascadeDelete_removesStagesWhenSessionDeleted() =
        runTest {
            // Given: session and its stages
            val stages =
                listOf(
                    SleepStageEntity(
                        sessionId = "session1",
                        stageType = "DEEP",
                        startTime = 0,
                        endTime = 1_800_000,
                        durationMinutes = 30,
                    ),
                )
            dao.upsertAll(stages)

            // When: delete the session
            sessionDao.deleteById("session1")

            // Then: stages are cascade-deleted
            val result = mutableListOf<SleepStageEntity>()
            val job =
                kotlinx.coroutines.launch {
                    dao.observeStagesForSession("session1").collect { result.addAll(it) }
                }

            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.delay(100)
                job.cancel()
            }

            assertEquals(0, result.size, "Expected stages to be cascade-deleted")
        }
}
