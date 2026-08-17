package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupLocation
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private lateinit var vitalsLayoutRepo: VitalsLayoutRepository
    private lateinit var sleepLayoutRepo: SleepLayoutRepository
    private lateinit var workoutsLayoutRepo: WorkoutsLayoutRepository
    private lateinit var workoutDetailLayoutRepo: WorkoutDetailLayoutRepository
    private lateinit var auditTrailRepository: FakeAuditTrailRepository
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
                every { userPreferences } returns
                    flowOf(
                        mockk(relaxed = true) {
                            coEvery { goalSleepHours } returns 8.0f
                            coEvery { syncPreference } returns SyncPreference.ALWAYS
                            coEvery { backgroundSyncEnabled } returns true
                            coEvery { backgroundSyncIntervalMinutes } returns 180
                            coEvery { hrrToleranceSeconds } returns 45
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
                every { dashboardCardConfigurations() } returns flowOf(emptyList())
            }
        vitalsLayoutRepo =
            mockk<VitalsLayoutRepository>(relaxed = true).apply {
                every { vitalsCardConfigurations() } returns flowOf(emptyList())
                every { vitalsChartConfigurations() } returns flowOf(emptyList())
            }
        sleepLayoutRepo =
            mockk<SleepLayoutRepository>(relaxed = true).apply {
                every { sleepTopCardConfigurations() } returns flowOf(emptyList())
                every { sleepChartConfigurations() } returns flowOf(emptyList())
                every { sleepMetricCardConfigurations() } returns flowOf(emptyList())
            }
        workoutsLayoutRepo =
            mockk<WorkoutsLayoutRepository>(relaxed = true).apply {
                every { workoutCardConfigurations() } returns flowOf(emptyList())
                every { workoutChartConfigurations() } returns flowOf(emptyList())
                every { workoutHistoryConfigurations() } returns flowOf(emptyList())
            }
        workoutDetailLayoutRepo =
            mockk<WorkoutDetailLayoutRepository>(relaxed = true).apply {
                every { allLayouts() } returns flowOf(emptyMap())
            }
        auditTrailRepository = FakeAuditTrailRepository()
        manager =
            LocalBackupManager(
                context,
                db,
                settingsRepo,
                cardConfigRepo,
                vitalsLayoutRepo,
                sleepLayoutRepo,
                workoutsLayoutRepo,
                workoutDetailLayoutRepo,
                encryptionManager,
                auditTrailRepository,
                Dispatchers.Unconfined,
            )
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
    fun createBackup_recordsBackupCreatedAuditEvent() =
        runTest {
            val result = manager.createBackup()

            assertTrue(result.isSuccess)
            assertEquals(listOf(AuditEvent.Type.BACKUP_CREATED), auditTrailRepository.events.map { it.type })
            assertEquals(null, auditTrailRepository.events.single().detail)
        }

    @Test
    fun createBackup_preservesSuccessWhenAuditAppendFails() =
        runTest {
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.BACKUP_CREATED) RuntimeException("audit unavailable") else null
            }

            val result = manager.createBackup()

            assertTrue(result.isSuccess)
            assertNotNull(result.getOrNull())
        }

    @Test
    fun createBackup_rethrowsCancellationFromAuditAppend() =
        runTest {
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.BACKUP_CREATED) CancellationException("cancel audit") else null
            }

            assertFailsWith<CancellationException> {
                manager.createBackup()
            }
        }

    @Test
    fun reencryptBackups_preservesSuccessWhenSuccessAuditAppendFails() =
        runTest {
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.KEY_ROTATED) RuntimeException("audit unavailable") else null
            }

            val result = manager.reencryptBackups(oldPassword = null, newPassword = "new_password")

            assertTrue(result.isSuccess)
        }

    @Test
    fun reencryptBackups_preservesOriginalFailureWhenFailureAuditAppendFails() =
        runTest {
            val originalFailure = RuntimeException("backup listing failed")
            coEvery { settingsRepo.userPreferences } throws originalFailure
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.KEY_ROTATION_FAILED) RuntimeException("audit unavailable") else null
            }

            val result = manager.reencryptBackups(oldPassword = null, newPassword = "new_password")

            assertTrue(result.isFailure)
            assertEquals(originalFailure::class, result.exceptionOrNull()!!::class)
            assertEquals(originalFailure.message, result.exceptionOrNull()?.message)
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
                        requestedDisplayMode = DashboardCardDisplayMode.BAR,
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
            assertTrue(backupJson.contains("\"requestedDisplayMode\":\"BAR\""))
        }

    @Test
    fun createBackup_writesVitalsLayoutToPreferences() =
        runTest {
            val vitalsCards =
                listOf(
                    CardConfiguration(
                        cardId = CardId.RESTING_HR,
                        isVisible = true,
                        position = 0,
                    ),
                    CardConfiguration(
                        cardId = CardId.HRV,
                        isVisible = false,
                        position = 1,
                        requestedDisplayMode = DashboardCardDisplayMode.BAR,
                    ),
                )
            val vitalsCharts =
                listOf(
                    VitalsChartConfiguration(
                        chartId = VitalsChartId.HRV_TREND,
                        isVisible = true,
                        position = 0,
                    ),
                    VitalsChartConfiguration(
                        chartId = VitalsChartId.BODY_TEMP_TREND,
                        isVisible = false,
                        position = 3,
                    ),
                )
            coEvery { vitalsLayoutRepo.vitalsCardConfigurations() } returns flowOf(vitalsCards)
            coEvery { vitalsLayoutRepo.vitalsChartConfigurations() } returns flowOf(vitalsCharts)

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
            val vitalsCardsJson = preferences.getJSONArray("vitalsCards")
            val vitalsChartsJson = preferences.getJSONArray("vitalsCharts")

            assertEquals(2, vitalsCardsJson.length())
            assertEquals("RESTING_HR", vitalsCardsJson.getJSONObject(0).getString("cardId"))
            assertTrue(vitalsCardsJson.getJSONObject(0).getBoolean("isVisible"))
            assertEquals(0, vitalsCardsJson.getJSONObject(0).getInt("position"))
            assertEquals("HRV", vitalsCardsJson.getJSONObject(1).getString("cardId"))
            assertTrue(!vitalsCardsJson.getJSONObject(1).getBoolean("isVisible"))
            assertEquals(1, vitalsCardsJson.getJSONObject(1).getInt("position"))
            assertTrue(backupJson.contains("\"requestedDisplayMode\":\"BAR\""))

            assertEquals(2, vitalsChartsJson.length())
            assertEquals("HRV_TREND", vitalsChartsJson.getJSONObject(0).getString("chartId"))
            assertTrue(vitalsChartsJson.getJSONObject(0).getBoolean("isVisible"))
            assertEquals(0, vitalsChartsJson.getJSONObject(0).getInt("position"))
            assertEquals("BODY_TEMP_TREND", vitalsChartsJson.getJSONObject(1).getString("chartId"))
            assertTrue(!vitalsChartsJson.getJSONObject(1).getBoolean("isVisible"))
            assertEquals(3, vitalsChartsJson.getJSONObject(1).getInt("position"))
        }

    @Test
    fun createBackup_writesSleepLayoutToPreferences() =
        runTest {
            val sleepTopCards =
                listOf(
                    SleepTopCardConfiguration(
                        cardId = SleepTopCardId.SLEEP_SCORE,
                        isVisible = true,
                        position = 0,
                    ),
                    SleepTopCardConfiguration(
                        cardId = SleepTopCardId.SLEEP_DURATION_GAUGE,
                        isVisible = false,
                        position = 1,
                    ),
                )
            val sleepCharts =
                listOf(
                    SleepChartConfiguration(
                        chartId = SleepChartId.SLEEP_DURATION_TREND,
                        isVisible = true,
                        position = 0,
                    ),
                )
            val sleepMetricCards =
                listOf(
                    SleepMetricCardConfiguration(
                        cardId = SleepMetricCardId.CIRCADIAN_CONSISTENCY,
                        isVisible = true,
                        position = 0,
                    ),
                )
            coEvery { sleepLayoutRepo.sleepTopCardConfigurations() } returns flowOf(sleepTopCards)
            coEvery { sleepLayoutRepo.sleepChartConfigurations() } returns flowOf(sleepCharts)
            coEvery { sleepLayoutRepo.sleepMetricCardConfigurations() } returns flowOf(sleepMetricCards)

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
            val topCardsJson = preferences.getJSONArray("sleepTopCards")
            val chartsJson = preferences.getJSONArray("sleepCharts")
            val metricCardsJson = preferences.getJSONArray("sleepMetricCards")

            assertEquals(2, topCardsJson.length())
            assertEquals("SLEEP_SCORE", topCardsJson.getJSONObject(0).getString("cardId"))
            assertTrue(topCardsJson.getJSONObject(0).getBoolean("isVisible"))
            assertEquals(0, topCardsJson.getJSONObject(0).getInt("position"))

            assertEquals(1, chartsJson.length())
            assertEquals("SLEEP_DURATION_TREND", chartsJson.getJSONObject(0).getString("chartId"))
            assertTrue(chartsJson.getJSONObject(0).getBoolean("isVisible"))

            assertEquals(1, metricCardsJson.length())
            assertEquals("CIRCADIAN_CONSISTENCY", metricCardsJson.getJSONObject(0).getString("cardId"))
            assertTrue(metricCardsJson.getJSONObject(0).getBoolean("isVisible"))
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
    fun createBackup_writesHrrToleranceSecondsToPreferences() =
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

            assertEquals(45, preferences.getInt("hrrToleranceSeconds"))
        }

    @Test
    fun createBackup_exportsRawVitalsTables() =
        runTest {
            db.weightRecordDao().upsertAll(
                listOf(
                    app.readylytics.health.data.local.entity.WeightRecordEntity(
                        id = "w1",
                        timestampMs = 1000L,
                        weightKg = 70.5f,
                    ),
                ),
            )
            db.bodyTemperatureRecordDao().upsertAll(
                listOf(
                    app.readylytics.health.data.local.entity.BodyTemperatureRecordEntity(
                        id = "bt1",
                        timestampMs = 1000L,
                        celsius = 36.8f,
                    ),
                ),
            )

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
            val json = JSONObject(backupJson)

            assertEquals(1, json.getJSONObject("rowCounts").getInt("weightRecords"))
            assertEquals(1, json.getJSONObject("rowCounts").getInt("bodyTemperatureRecords"))
            assertEquals(0, json.getJSONObject("rowCounts").getInt("stepRecords"))
            assertEquals("w1", json.getJSONArray("weightRecords").getJSONObject(0).getString("id"))
            assertEquals(
                36.8,
                json.getJSONArray("bodyTemperatureRecords").getJSONObject(0).getDouble("celsius"),
                0.01,
            )
            assertEquals(0, json.getJSONArray("bodyFatRecords").length())
            assertEquals(0, json.getJSONArray("bloodPressureRecords").length())
            assertEquals(0, json.getJSONArray("oxygenSaturationRecords").length())
            assertEquals(0, json.getJSONArray("stepRecords").length())
        }

    @Test
    fun createBackup_parallelRowCounts_areCompleteAndAccurate() =
        runTest {
            db.weightRecordDao().upsertAll(
                listOf(
                    app.readylytics.health.data.local.entity.WeightRecordEntity(
                        id = "w1",
                        timestampMs = 1000L,
                        weightKg = 70.5f,
                    ),
                ),
            )
            db.stepRecordDao().upsertAll(
                listOf(
                    app.readylytics.health.data.local.entity.StepRecordEntity(
                        id = "s1",
                        startTime = 1000L,
                        endTime = 2000L,
                        count = 5000L,
                    ),
                ),
            )

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
            val rowCounts = JSONObject(backupJson).getJSONObject("rowCounts")

            // Seeded tables.
            assertEquals(1, rowCounts.getInt("weightRecords"))
            assertEquals(1, rowCounts.getInt("stepRecords"))
            // Empty tables must still appear, counted correctly, in the parallel block.
            assertEquals(0, rowCounts.getInt("sleepSessions"))
            assertEquals(0, rowCounts.getInt("heartRateRecords"))
            assertEquals(0, rowCounts.getInt("hrvRecords"))
            assertEquals(0, rowCounts.getInt("workouts"))
            assertEquals(0, rowCounts.getInt("dailySummaries"))
            assertEquals(0, rowCounts.getInt("bodyFatRecords"))
            assertEquals(0, rowCounts.getInt("bloodPressureRecords"))
            assertEquals(0, rowCounts.getInt("oxygenSaturationRecords"))
            assertEquals(0, rowCounts.getInt("bodyTemperatureRecords"))
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
                    every { userPreferences } returns
                        flowOf(
                            app.readylytics.health.data.preferences.UserPreferences(
                                backupDirectoryUri = safUri.toString(),
                            ),
                        )
                }
            manager =
                LocalBackupManager(
                    context,
                    db,
                    settingsRepo,
                    cardConfigRepo,
                    vitalsLayoutRepo,
                    sleepLayoutRepo,
                    workoutsLayoutRepo,
                    workoutDetailLayoutRepo,
                    encryptionManager,
                    auditTrailRepository,
                    Dispatchers.Unconfined,
                )

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
                    every { userPreferences } returns
                        flowOf(
                            app.readylytics.health.data.preferences.UserPreferences(
                                backupPasswordHash = null,
                            ),
                        )
                }
            manager =
                LocalBackupManager(
                    context,
                    db,
                    settingsRepo,
                    cardConfigRepo,
                    vitalsLayoutRepo,
                    sleepLayoutRepo,
                    workoutsLayoutRepo,
                    workoutDetailLayoutRepo,
                    encryptionManager,
                    auditTrailRepository,
                    Dispatchers.Unconfined,
                )

            val result = manager.createBackup()

            assertTrue(result.isFailure)
            val leakedJson =
                context.cacheDir
                    .listFiles { file -> file.name.startsWith("backup_") && file.name.endsWith(".json") }
                    .orEmpty()
            assertFalse(leakedJson.any(), "Plaintext backup JSON temp files must be removed after failure")
        }

    class FakeBackupStore(
        var files: MutableMap<String, ByteArray> = mutableMapOf(),
        var failPublish: Boolean = false,
        var partialPublish: Boolean = false,
    ) : BackupStore {
        var lastPublishedSourceLength: Long = -1

        override suspend fun list(): List<BackupFileInfo> =
            files.map { (name, bytes) ->
                BackupFileInfo(
                    name = name,
                    lastModified = System.currentTimeMillis(),
                    sizeBytes = bytes.size.toLong(),
                    location = BackupLocation("fake://$name"),
                )
            }

        override suspend fun read(location: BackupLocation): java.io.InputStream {
            val name = location.value.removePrefix("fake://")
            val bytes = files[name] ?: error("File not found")
            return bytes.inputStream()
        }

        override suspend fun publish(
            source: File,
            name: String,
        ) {
            lastPublishedSourceLength = source.length()
            if (failPublish) throw java.io.IOException("Disk full / rename failed")
            if (partialPublish) throw java.io.IOException("SAF stream closed mid-write")
            files[name] = source.readBytes()
        }

        override suspend fun delete(location: BackupLocation) {
            val name = location.value.removePrefix("fake://")
            files.remove(name)
        }

        override suspend fun prune(retentionPeriodMs: Long) {}
    }

    @Test
    fun `reencryptBackups failure preserves original backup and returns failure`() =
        runTest {
            val fakeStore = FakeBackupStore()
            val sampleZipFile = File(context.cacheDir, "sample_test.zip")
            val zip = ZipFile(sampleZipFile, "old_password".toCharArray())
            val tempPlain =
                File(context.cacheDir, "backup_2026-05-15_100000.json").apply { writeText("""{"version":1}""") }
            zip.addFile(
                tempPlain,
                ZipParameters().apply {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                },
            )
            zip.close()
            tempPlain.delete()

            val originalBytes = sampleZipFile.readBytes()
            sampleZipFile.delete()
            fakeStore.files["backup_2026-05-15_100000.zip"] = originalBytes
            fakeStore.failPublish = true

            val testManager =
                LocalBackupManager(
                    context,
                    db,
                    settingsRepo,
                    cardConfigRepo,
                    vitalsLayoutRepo,
                    sleepLayoutRepo,
                    workoutsLayoutRepo,
                    workoutDetailLayoutRepo,
                    encryptionManager,
                    auditTrailRepository,
                    Dispatchers.Unconfined,
                    backupStoreFactory =
                        object : BackupStoreFactory {
                            override fun create(customUri: Uri?): BackupStore = fakeStore

                            override fun createDefault(): BackupStore = fakeStore
                        },
                )

            val result = testManager.reencryptBackups("old_password", "new_password")
            assertTrue(result.isFailure, "reencryptBackups should fail when publish fails")

            // Assert original archive is preserved and opens with OLD password
            val restoredBytes = fakeStore.files["backup_2026-05-15_100000.zip"]
            assertNotNull(restoredBytes)
            val checkZipFile = File(context.cacheDir, "check_orig.zip").apply { writeBytes(restoredBytes) }
            val checkZip = ZipFile(checkZipFile, "old_password".toCharArray())
            assertTrue(checkZip.isValidZipFile)
            checkZip.close()
            checkZipFile.delete()
        }

    @Test
    fun `reencryptBackups never calls publish with zero length source`() =
        runTest {
            val fakeStore = FakeBackupStore()
            val sampleZipFile = File(context.cacheDir, "sample_test2.zip")
            val zip = ZipFile(sampleZipFile, "old_password".toCharArray())
            val tempPlain =
                File(context.cacheDir, "backup_2026-05-15_100000.json").apply { writeText("""{"version":1}""") }
            zip.addFile(
                tempPlain,
                ZipParameters().apply {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                },
            )
            zip.close()
            tempPlain.delete()
            fakeStore.files["backup_2026-05-15_100000.zip"] = sampleZipFile.readBytes()
            sampleZipFile.delete()

            val testManager =
                LocalBackupManager(
                    context,
                    db,
                    settingsRepo,
                    cardConfigRepo,
                    vitalsLayoutRepo,
                    sleepLayoutRepo,
                    workoutsLayoutRepo,
                    workoutDetailLayoutRepo,
                    encryptionManager,
                    auditTrailRepository,
                    Dispatchers.Unconfined,
                    backupStoreFactory =
                        object : BackupStoreFactory {
                            override fun create(customUri: Uri?): BackupStore = fakeStore

                            override fun createDefault(): BackupStore = fakeStore
                        },
                )

            val result = testManager.reencryptBackups("old_password", "new_password")
            assertTrue(result.isSuccess)
            assertTrue(fakeStore.lastPublishedSourceLength > 0, "Source length must be > 0")
        }

    @Test
    fun `reencryptBackups does not write plaintext JSON files to tempDir during rotation`() =
        runTest {
            val sampleZipFile = File(context.cacheDir, "sample_stream_test.zip")
            val zip = ZipFile(sampleZipFile, "old_pass".toCharArray())
            val tempPlain =
                File(context.cacheDir, "plain_stream.json").apply { writeText("""{"sensitiveHealthData":123}""") }
            zip.addFile(
                tempPlain,
                ZipParameters().apply {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                },
            )
            zip.close()
            tempPlain.delete()

            val fakeStore =
                FakeBackupStore(
                    files = mutableMapOf("backup_2026-05-15_100000.zip" to sampleZipFile.readBytes()),
                )
            sampleZipFile.delete()

            val tempDir = File(context.cacheDir, "reencrypt_temp")

            val testManager =
                LocalBackupManager(
                    context,
                    db,
                    settingsRepo,
                    cardConfigRepo,
                    vitalsLayoutRepo,
                    sleepLayoutRepo,
                    workoutsLayoutRepo,
                    workoutDetailLayoutRepo,
                    encryptionManager,
                    auditTrailRepository,
                    Dispatchers.Unconfined,
                    backupStoreFactory =
                        object : BackupStoreFactory {
                            override fun create(customUri: Uri?): BackupStore = fakeStore

                            override fun createDefault(): BackupStore = fakeStore
                        },
                )

            val result = testManager.reencryptBackups("old_pass", "new_pass")
            assertTrue(result.isSuccess)

            val jsonFiles = tempDir.listFiles { f -> f.name.endsWith(".json") }
            assertTrue(jsonFiles.isNullOrEmpty(), "No plaintext JSON files must exist in tempDir")

            val newBytes = fakeStore.files["backup_2026-05-15_100000.zip"]
            assertNotNull(newBytes)
            val checkZipFile = File(context.cacheDir, "check_reencrypted.zip").apply { writeBytes(newBytes) }
            val checkZip = ZipFile(checkZipFile, "new_pass".toCharArray())
            assertTrue(checkZip.isValidZipFile)
            val header = checkZip.fileHeaders.firstOrNull { it.fileName == "plain_stream.json" }
            assertNotNull(header)
            val content = checkZip.getInputStream(header).bufferedReader().readText()
            assertEquals("""{"sensitiveHealthData":123}""", content)
            checkZip.close()
            checkZipFile.delete()
        }

    private class FakeAuditTrailRepository : AuditTrailRepository {
        val events = mutableListOf<AuditEvent>()
        var appendFailure: (AuditEvent) -> Throwable? = { null }

        override suspend fun append(event: AuditEvent) {
            appendFailure(event)?.let { throw it }
            events += event
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEvent>> =
            flowOf(events.sortedByDescending { it.occurredAt }.take(limit))
    }
}
