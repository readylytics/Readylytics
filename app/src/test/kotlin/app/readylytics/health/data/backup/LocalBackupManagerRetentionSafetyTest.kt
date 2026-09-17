package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SyncPreference
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalBackupManagerRetentionSafetyTest {
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
        every { encryptionManager.decrypt(any()) } returns "test_password"

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
        manager = buildManager()
    }

    private fun buildManager(
        customSettingsRepo: SettingsRepository = settingsRepo,
        customStoreFactory: BackupStoreFactory = DefaultBackupStoreFactory(context),
        customWriter: BackupStreamWriter? = null,
    ): LocalBackupManager {
        kotlinx.coroutines.runBlocking {
            db.healthMutationStateDao().upsert(
                app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity(
                    id = 1,
                    sourceGeneration = 0,
                    backfillAfterSourceRef = 0,
                ),
            )
        }
        val coordinator =
            app.readylytics.health.core.database.data.local
                .HealthMutationCoordinatorImpl(db.healthMutationStateDao())
        val layoutRepos =
            RestoreLayoutRepositories(
                cardConfigRepo,
                vitalsLayoutRepo,
                sleepLayoutRepo,
                workoutsLayoutRepo,
                workoutDetailLayoutRepo,
            )
        val backupStreamWriter = customWriter ?: BackupStreamWriter(db, CoverageBackupWriter(db))
        val exporter =
            BackupSnapshotExporter(
                db,
                coordinator,
                customSettingsRepo,
                layoutRepos,
                backupStreamWriter,
            )
        return LocalBackupManager(
            context,
            customSettingsRepo,
            exporter,
            encryptionManager,
            auditTrailRepository,
            Dispatchers.Unconfined,
            customStoreFactory,
        )
    }

    private fun seedValidArchive(
        dir: File,
        isStale: Boolean = false,
        name: String = "backup_2026-05-01_100000.zip",
    ): File {
        dir.mkdirs()
        val file = File(dir, name)
        ZipFile(file, "test_password".toCharArray()).use { zip ->
            val jsonFile =
                File(context.cacheDir, "seed.json").apply {
                    writeText(
                        """
                        {
                          "schemaVersion": 19,
                          "exportedAt": "2026-05-01T10:00:00Z",
                          "rowCounts": {}
                        }
                        """.trimIndent(),
                    )
                }
            try {
                val params =
                    ZipParameters().apply {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                zip.addFile(jsonFile, params)
            } finally {
                jsonFile.delete()
            }
        }
        if (isStale) {
            file.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        }
        return file
    }

    private fun mockStore(backupStore: BackupStore = mockk(relaxed = true)): Pair<BackupStore, BackupStoreFactory> {
        val factory =
            object : BackupStoreFactory {
                override fun create(customUri: Uri?): BackupStore = backupStore

                override fun createDefault(): BackupStore = backupStore
            }
        return backupStore to factory
    }

    @After
    fun tearDown() {
        db.close()
        backupDir.deleteRecursively()
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

            val customSettingsRepo =
                mockk<SettingsRepository>().apply {
                    every { userPreferences } returns
                        flowOf(
                            app.readylytics.health.core.model.data.preferences.UserPreferences(
                                backupDirectoryUri = safUri.toString(),
                                backupPasswordHash = "hashed_password",
                            ),
                        )
                }
            val safManager = buildManager(customSettingsRepo = customSettingsRepo)

            val now = System.currentTimeMillis()
            val eightDaysAgo = now - (8L * 24 * 60 * 60 * 1000)
            val oneDayAgo = now - (1L * 24 * 60 * 60 * 1000)

            val staleFile = File(safDir, "backup_2026-05-08_100000.zip")
            val recentFile = File(safDir, "backup_2026-05-15_100000.zip")

            staleFile.writeText("{}")
            recentFile.writeText("{}")

            staleFile.setLastModified(eightDaysAgo)
            recentFile.setLastModified(oneDayAgo)

            val result = safManager.createBackup()
            assertTrue(result.isSuccess)

            assertTrue(!staleFile.exists(), "Stale SAF file should be deleted")
            assertTrue(recentFile.exists(), "Recent SAF file should be retained")

            safDir.deleteRecursively()
        }

    @Test
    fun createBackup_missingPassword_removesPlaintextTempJson() =
        runTest {
            val unconfiguredSettingsRepo =
                mockk<SettingsRepository>().apply {
                    every { userPreferences } returns
                        flowOf(
                            mockk(relaxed = true) {
                                coEvery { backupPasswordHash } returns null
                            },
                        )
                }
            val failingManager = buildManager(customSettingsRepo = unconfiguredSettingsRepo)

            val result = failingManager.createBackup()

            assertTrue(result.isFailure)
            val leakedJson =
                context.cacheDir
                    .listFiles { file -> file.name.endsWith(".json") }
                    ?.toList()
                    .orEmpty() +
                    File(context.cacheDir, "backup-staging")
                        .listFiles { file -> file.name.endsWith(".json") }
                        ?.toList()
                        .orEmpty()
            assertFalse(leakedJson.any(), "Plaintext backup JSON temp files must be removed after failure")
        }

    @Test
    fun createBackup_ioExceptionDuringJsonWrite_doesNotPrune() =
        runTest {
            val (backupStore, storeFactory) = mockStore()
            val failingWriter = mockk<BackupStreamWriter>()
            coEvery { failingWriter.writeJsonStreaming(any(), any(), any()) } throws IOException("disk full")
            val failingManager = buildManager(customStoreFactory = storeFactory, customWriter = failingWriter)

            val result = failingManager.createBackup()

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { backupStore.prune(any()) }
        }

    @Test
    fun createBackup_ioExceptionDuringZipCreation_doesNotPrune() =
        runTest {
            val (backupStore, storeFactory) = mockStore()
            every { encryptionManager.decrypt(any()) } throws IOException("keystore error")
            val failingManager = buildManager(customStoreFactory = storeFactory)

            val result = failingManager.createBackup()

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { backupStore.prune(any()) }
        }

    @Test
    fun createBackup_ioExceptionDuringPublication_doesNotPrune() =
        runTest {
            val (backupStore, storeFactory) = mockStore()
            coEvery { backupStore.publish(any(), any()) } throws IOException("storage disconnected")
            val failingManager = buildManager(customStoreFactory = storeFactory)

            val result = failingManager.createBackup()

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { backupStore.prune(any()) }
        }

    @Test
    fun createBackup_ioExceptionDuringPublicationReadBack_doesNotPrune() =
        runTest {
            val (backupStore, storeFactory) = mockStore()
            coEvery { backupStore.read(any()) } throws IOException("read corrupted")
            val failingManager = buildManager(customStoreFactory = storeFactory)

            val result = failingManager.createBackup()

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { backupStore.prune(any()) }
        }

    @Test
    fun createBackup_failurePreservesRealFileSeededArchive() =
        runTest {
            val seededFile = seedValidArchive(backupDir, isStale = true)
            val initialBytes = seededFile.readBytes()
            val initialList = backupDir.listFiles()?.map { it.name }
            val failingWriter = mockk<BackupStreamWriter>()
            coEvery { failingWriter.writeJsonStreaming(any(), any(), any()) } throws IOException("disk error")
            val failingManager = buildManager(customWriter = failingWriter)

            val result = failingManager.createBackup()

            assertTrue(result.isFailure)
            assertTrue(seededFile.exists(), "Prior valid archive must be preserved on failure")
            assertEquals(initialBytes.toList(), seededFile.readBytes().toList())
            assertEquals(initialList, backupDir.listFiles()?.map { it.name })
        }

    @Test
    fun createBackup_failurePreservesRealSafSeededArchive() =
        runTest {
            val safDir = File(context.cacheDir, "saf_backups_failure_test").apply { mkdirs() }
            try {
                val safUri = Uri.fromFile(safDir)
                val seededFile = seedValidArchive(safDir, isStale = true)
                val initialBytes = seededFile.readBytes()
                val initialList = safDir.listFiles()?.map { it.name }
                val customSettingsRepo =
                    mockk<SettingsRepository>().apply {
                        every { userPreferences } returns
                            flowOf(
                                app.readylytics.health.core.model.data.preferences.UserPreferences(
                                    backupDirectoryUri = safUri.toString(),
                                    backupPasswordHash = "hashed_password",
                                ),
                            )
                    }
                val failingWriter = mockk<BackupStreamWriter>()
                coEvery { failingWriter.writeJsonStreaming(any(), any(), any()) } throws IOException("disk error")
                val failingManager = buildManager(customSettingsRepo = customSettingsRepo, customWriter = failingWriter)

                val result = failingManager.createBackup()

                assertTrue(result.isFailure)
                assertTrue(seededFile.exists(), "Prior valid SAF archive must be preserved on failure")
                assertEquals(initialBytes.toList(), seededFile.readBytes().toList())
                assertEquals(initialList, safDir.listFiles()?.map { it.name })
            } finally {
                safDir.deleteRecursively()
            }
        }

    internal class FakeAuditTrailRepository : AuditTrailRepository {
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
