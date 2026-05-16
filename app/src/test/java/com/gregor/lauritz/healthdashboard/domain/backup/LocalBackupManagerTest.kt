package com.gregor.lauritz.healthdashboard.domain.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gregor.lauritz.healthdashboard.data.local.HealthDatabase
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalBackupManagerTest {
    private lateinit var context: Context
    private lateinit var db: HealthDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var manager: LocalBackupManager
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        backupDir = File(context.filesDir, "backups")
        backupDir.deleteRecursively()

        db =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        settingsRepo =
            mockk<SettingsRepository>().apply {
                coEvery { userPreferences } returns
                    flowOf(
                        mockk(relaxed = true) {
                            coEvery { goalSleepHours } returns 8.0f
                            coEvery { syncPreference } returns SyncPreference.ALWAYS
                            coEvery { appTheme } returns AppTheme.DARK
                            coEvery { backupSchedule } returns BackupSchedule.DAILY
                            coEvery { birthDay } returns 1
                            coEvery { birthMonth } returns 1
                            coEvery { birthYear } returns 2000
                        },
                    )
            }

        manager = LocalBackupManager(context, db, settingsRepo)
    }

    @After
    fun tearDown() {
        db.close()
        backupDir.deleteRecursively()
    }

    @Test
    fun createBackup_writesJsonFile() =
        runTest {
            val result = manager.createBackup()

            assertTrue(result.isSuccess)
            val file = result.getOrNull()
            assertNotNull(file)
            assertTrue(file.exists())
            assertTrue(file.name.matches(Regex("backup_\\d{4}-\\d{2}-\\d{2}_\\d{6}\\.json")))
        }

    @Test
    fun createBackup_rowCountsMatchInsertedData() =
        runTest {
            db
                .sleepSessionDao()
                .upsertAll(
                    listOf(
                        SleepSessionEntity(
                            id = "sleep_1",
                            startTime = Instant.now().toEpochMilli(),
                            endTime = Instant.now().toEpochMilli() + 28800000,
                            durationMinutes = 480,
                            efficiency = 0.85f,
                            deepSleepMinutes = 120,
                            lightSleepMinutes = 240,
                            remSleepMinutes = 120,
                            awakeMinutes = 0,
                            deviceName = "Device1",
                        ),
                    ),
                )

            db
                .heartRateDao()
                .upsertAll(
                    listOf(
                        HeartRateRecordEntity(
                            id = "hr_1",
                            sessionId = "sleep_1",
                            recordType = "sleep",
                            timestampMs = Instant.now().toEpochMilli(),
                            beatsPerMinute = 60,
                        ),
                    ),
                )

            val result = manager.createBackup()
            assertTrue(result.isSuccess)

            val file = result.getOrNull()
            val json = JSONObject(file!!.readText())
            val rowCounts = json.getJSONObject("rowCounts")

            assertEquals(1, rowCounts.getInt("sleepSessions"))
            assertEquals(1, rowCounts.getInt("heartRateRecords"))
            assertEquals(0, rowCounts.getInt("hrvRecords"))
            assertEquals(0, rowCounts.getInt("workouts"))
            assertEquals(0, rowCounts.getInt("dailySummaries"))
        }

    @Test
    fun createBackup_prunesFilesOlderThan7Days() =
        runTest {
            backupDir.mkdirs()

            val now = System.currentTimeMillis()
            val eightDaysAgo = now - (8L * 24 * 60 * 60 * 1000)
            val oneDayAgo = now - (1L * 24 * 60 * 60 * 1000)

            val staleFile1 = File(backupDir, "backup_2026-05-08_100000.json")
            val staleFile2 = File(backupDir, "backup_2026-05-07_100000.json")
            val recentFile = File(backupDir, "backup_2026-05-15_100000.json")

            staleFile1.writeText("{}")
            staleFile2.writeText("{}")
            recentFile.writeText("{}")

            staleFile1.setLastModified(eightDaysAgo)
            staleFile2.setLastModified(eightDaysAgo)
            recentFile.setLastModified(oneDayAgo)

            val result = manager.createBackup()
            assertTrue(result.isSuccess)

            assertTrue(!staleFile1.exists(), "Stale file 1 should be deleted")
            assertTrue(!staleFile2.exists(), "Stale file 2 should be deleted")
            assertTrue(recentFile.exists(), "Recent file should be retained")
        }

    @Test
    fun listBackups_sortedNewestFirst() =
        runTest {
            backupDir.mkdirs()

            val now = System.currentTimeMillis()
            val file1 = File(backupDir, "backup_2026-05-16_100000.json")
            val file2 = File(backupDir, "backup_2026-05-15_100000.json")
            val file3 = File(backupDir, "backup_2026-05-14_100000.json")

            file1.writeText("{}")
            file2.writeText("{}")
            file3.writeText("{}")

            file3.setLastModified(now - 48 * 60 * 60 * 1000)
            file2.setLastModified(now - 24 * 60 * 60 * 1000)
            file1.setLastModified(now)

            val backups = manager.listBackups()

            assertEquals(3, backups.size)
            assertEquals("backup_2026-05-16_100000.json", backups[0].name)
            assertEquals("backup_2026-05-15_100000.json", backups[1].name)
            assertEquals("backup_2026-05-14_100000.json", backups[2].name)
        }
}
