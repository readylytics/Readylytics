package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class RollingWindowTest {

    private lateinit var database: HealthDatabase
    private lateinit var workoutDao: WorkoutDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workoutDao = database.workoutDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `getWorkoutsInRange filters correctly`() = runTest {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val yesterdayMs = today.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val twoDaysAgoMs = today.minusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli()

        workoutDao.upsertAll(listOf(
            WorkoutRecordEntity(id = "1", startTime = todayMs, endTime = todayMs + 1000, exerciseType = "Running", durationMinutes = 10, trimp = 10f, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f, avgHr = 0),
            WorkoutRecordEntity(id = "2", startTime = yesterdayMs, endTime = yesterdayMs + 1000, exerciseType = "Running", durationMinutes = 10, trimp = 20f, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f, avgHr = 0),
            WorkoutRecordEntity(id = "3", startTime = twoDaysAgoMs, endTime = twoDaysAgoMs + 1000, exerciseType = "Running", durationMinutes = 10, trimp = 30f, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f, avgHr = 0)
        ))

        // 7-day window (includes all)
        val sevenDayStart = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val sevenDayResult = workoutDao.getWorkoutsInRange(sevenDayStart, todayMs + 86400000)
        assertEquals(3, sevenDayResult.size)

        // 1-day window (includes only today)
        val oneDayResult = workoutDao.getWorkoutsInRange(todayMs, todayMs + 86400000)
        assertEquals(1, oneDayResult.size)
        assertEquals("1", oneDayResult[0].id)
    }

    @Test
    fun `getDailyTrimp aggregates by day correctly`() = runTest {
        val zoneId = ZoneId.systemDefault()
        val tzOffsetMs = zoneId.rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
        val today = LocalDate.now(zoneId)
        val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val yesterdayMs = today.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        workoutDao.upsertAll(listOf(
            WorkoutRecordEntity(id = "1", startTime = todayMs + 1000, endTime = todayMs + 2000, exerciseType = "Running", durationMinutes = 10, trimp = 10f, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f, avgHr = 0),
            WorkoutRecordEntity(id = "2", startTime = todayMs + 5000, endTime = todayMs + 6000, exerciseType = "Running", durationMinutes = 10, trimp = 15f, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f, avgHr = 0),
            WorkoutRecordEntity(id = "3", startTime = yesterdayMs + 1000, endTime = yesterdayMs + 2000, exerciseType = "Running", durationMinutes = 10, trimp = 20f, zone1Minutes = 0f, zone2Minutes = 0f, zone3Minutes = 0f, zone4Minutes = 0f, zone5Minutes = 0f, avgHr = 0)
        ))

        val result = workoutDao.getDailyTrimp(yesterdayMs, todayMs + 86400000, tzOffsetMs)
        
        // Should have 2 entries: yesterday (20) and today (25)
        assertEquals(2, result.size)
        assertEquals(20f, result[0])
        assertEquals(25f, result[1])
    }
}
