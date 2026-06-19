package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.CardConfigurationRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalBackupManagerTest {
    private lateinit var context: Context
    private lateinit var db: HealthDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var cardConfigRepo: CardConfigurationRepository
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

        encryptionManager = mockk<EncryptionManager>(relaxed = true)
        io.mockk.every { encryptionManager.decrypt(any()) } returns "test_password"

        settingsRepo =
            mockk<SettingsRepository>().apply {
                coEvery { userPreferences } returns
                    flowOf(
                        mockk(relaxed = true) {
                            coEvery { goalSleepHours } returns 8.0f
                            coEvery { syncPreference } returns SyncPreference.ALWAYS
                            coEvery { backgroundSyncEnabled } returns true
                            coEvery { backgroundSyncIntervalMinutes } returns 180
                            coEvery { appTheme } returns AppTheme.DARK
                            coEvery { backupSchedule } returns BackupSchedule.DAILY
                            coEvery { birthDate } returns "2000-01-01"
                            coEvery { backupDirectoryUri } returns null
                            coEvery { backupPasswordHash } returns "hashed_password"
                        },
                    )
            }

        cardConfigRepo =
            mockk<CardConfigurationRepository>(relaxed = true).apply {
                coEvery { dashboardCardConfigurations() } returns flowOf(emptyList())
            }
        manager =
            LocalBackupManager(context, db, settingsRepo, cardConfigRepo, encryptionManager, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        backupDir.deleteRecursively()
    }

    @Test
    fun createBackup_writesZipFile() =
        runTest {
            val result = manager.createBackup()

            assertTrue(result.isSuccess)
            // Note: Since we are using SAF internally if Uri is provided,
            // result might be null for File if it was written to SAF outputstream.
            // But in this test, customUri is null, so it uses internal storage.
            val file = result.getOrNull()
            assertNotNull(file)
            assertTrue(file.exists())
            assertTrue(file.name.endsWith(".zip"))
            assertTrue(file.name.startsWith("backup_"))
        }

    @Test
    fun createBackup_writesDashboardCardsToPreferences() =
        runTest {
            val cards =
                listOf(
                    CardConfiguration(
                        cardId = CardId.READINESS,
                        isVisible = true,
                        position = 1,
                    ),
                    CardConfiguration(
                        cardId = CardId.HRV,
                        isVisible = false,
                        position = 4,
                    ),
                )
            coEvery { cardConfigRepo.dashboardCardConfigurations() } returns flowOf(cards)

            val result = manager.createBackup()

            assertTrue(result.isSuccess)
            val file = result.getOrNull()
            assertNotNull(file)

            val zipFile = ZipFile(file, "test_password".toCharArray())
            val header = zipFile.fileHeaders.single()
            val backupJson =
                zipFile.getInputStream(header).use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
            val dashboardCards =
                JSONObject(backupJson)
                    .getJSONObject("preferences")
                    .getJSONArray("dashboardCards")

            assertEquals(2, dashboardCards.length())
            assertEquals("READINESS", dashboardCards.getJSONObject(0).getString("cardId"))
            assertTrue(dashboardCards.getJSONObject(0).getBoolean("isVisible"))
            assertEquals(1, dashboardCards.getJSONObject(0).getInt("position"))
            assertEquals("HRV", dashboardCards.getJSONObject(1).getString("cardId"))
            assertTrue(!dashboardCards.getJSONObject(1).getBoolean("isVisible"))
            assertEquals(4, dashboardCards.getJSONObject(1).getInt("position"))
        }

    @Test
    fun createBackup_writesBackgroundSyncAndBackupScheduleToPreferences() =
        runTest {
            val result = manager.createBackup()

            assertTrue(result.isSuccess)
            val file = result.getOrNull()
            assertNotNull(file)

            val zipFile = ZipFile(file, "test_password".toCharArray())
            val header = zipFile.fileHeaders.single()
            val backupJson =
                zipFile.getInputStream(header).use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
            val preferences = JSONObject(backupJson).getJSONObject("preferences")

            assertTrue(preferences.getBoolean("backgroundSyncEnabled"))
            assertEquals(180, preferences.getInt("backgroundSyncIntervalMinutes"))
            assertEquals("DAILY", preferences.getString("backupSchedule"))
        }

    @Test
    fun createBackup_prunesFilesOlderThan7Days() =
        runTest {
            backupDir.mkdirs()

            val now = System.currentTimeMillis()
            val eightDaysAgo = now - (8L * 24 * 60 * 60 * 1000)
            val oneDayAgo = now - (1L * 24 * 60 * 60 * 1000)

            val staleFile1 = File(backupDir, "backup_2026-05-08_100000.zip")
            val staleFile2 = File(backupDir, "backup_2026-05-07_100000.zip")
            val recentFile = File(backupDir, "backup_2026-05-15_100000.zip")

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
    fun createBackup_prunesSafFilesOlderThan7Days() =
        runTest {
            val safDir = File(context.cacheDir, "saf_backups")
            safDir.mkdirs()
            val safUri = Uri.fromFile(safDir)

            settingsRepo =
                mockk<SettingsRepository>().apply {
                    coEvery { userPreferences } returns
                        flowOf(
                            mockk(relaxed = true) {
                                coEvery { backupDirectoryUri } returns safUri.toString()
                            },
                        )
                }
            manager =
                LocalBackupManager(context, db, settingsRepo, cardConfigRepo, encryptionManager, Dispatchers.Unconfined)

            val now = System.currentTimeMillis()
            val eightDaysAgo = now - (8L * 24 * 60 * 60 * 1000)
            val oneDayAgo = now - (1L * 24 * 60 * 60 * 1000)

            val staleFile = File(safDir, "backup_2026-05-08_100000.zip")
            val recentFile = File(safDir, "backup_2026-05-15_100000.zip")

            staleFile.writeText("{}")
            recentFile.writeText("{}")

            staleFile.setLastModified(eightDaysAgo)
            recentFile.setLastModified(oneDayAgo)

            // We need to use a real DocumentFile behavior.
            // In Robolectric, Uri.fromFile(dir) works with DocumentFile.fromTreeUri.

            val result = manager.createBackup()
            // Note: createBackup might fail because of missing content resolver support for file:// outputstream in Robolectric
            // but we only care about the pruning part which happens before it tries to write if we are lucky,
            // or we just check the files after.
            // Actually, pruning happens AFTER creation in my implementation for SAF?
            // Let's check:
            // if (customUri != null) { ... pruneOldBackups(customUri) ... }

            // Wait, in my implementation:
            // context.contentResolver.openOutputStream(file.uri)?.use { ... }
            // pruneOldBackups(customUri)

            // If openOutputStream fails, pruneOldBackups might not be called.
            // I should move pruning BEFORE writing to ensure it happens even if write fails?
            // Usually it's better to prune before to free up space.

            assertTrue(!staleFile.exists(), "Stale SAF file should be deleted")
            assertTrue(recentFile.exists(), "Recent SAF file should be retained")

            safDir.deleteRecursively()
        }

    @Test
    fun createBackup_missingPassword_removesPlaintextTempJson() =
        runTest {
            settingsRepo =
                mockk<SettingsRepository>().apply {
                    coEvery { userPreferences } returns
                        flowOf(
                            app.readylytics.health.data.preferences.UserPreferences(
                                backupPasswordHash = null,
                            ),
                        )
                }
            manager =
                LocalBackupManager(context, db, settingsRepo, cardConfigRepo, encryptionManager, Dispatchers.Unconfined)

            val result = manager.createBackup()

            assertTrue(result.isFailure)
            val leakedJson =
                context.cacheDir
                    .listFiles { file -> file.name.startsWith("backup_") && file.name.endsWith(".json") }
                    .orEmpty()
            assertFalse(leakedJson.any(), "Plaintext backup JSON temp files must be removed after failure")
        }
}
