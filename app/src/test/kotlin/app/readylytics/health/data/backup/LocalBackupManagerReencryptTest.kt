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
import app.readylytics.health.core.model.domain.backup.BackupFileInfo
import app.readylytics.health.core.model.domain.backup.BackupLocation
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
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
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalBackupManagerReencryptTest {
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
    ): LocalBackupManager {
        val layoutRepos =
            RestoreLayoutRepositories(
                cardConfigRepo,
                vitalsLayoutRepo,
                sleepLayoutRepo,
                workoutsLayoutRepo,
                workoutDetailLayoutRepo,
            )
        val backupStreamWriter = BackupStreamWriter(db, customSettingsRepo, layoutRepos)
        return LocalBackupManager(
            context,
            customSettingsRepo,
            backupStreamWriter,
            encryptionManager,
            auditTrailRepository,
            Dispatchers.Unconfined,
            customStoreFactory,
        )
    }

    @After
    fun tearDown() {
        db.close()
        backupDir.deleteRecursively()
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
                buildManager(
                    customStoreFactory =
                        object : BackupStoreFactory {
                            override fun create(customUri: Uri?): BackupStore = fakeStore

                            override fun createDefault(): BackupStore = fakeStore
                        },
                )

            val result = testManager.reencryptBackups("old_password", "new_password")
            assertTrue(result.isFailure, "reencryptBackups should fail when publish fails")

            val restoredBytes = fakeStore.files["backup_2026-05-15_100000.zip"]
            assertNotNull(restoredBytes)
            val checkZipFile = File(context.cacheDir, "check_orig.zip").apply { writeBytes(restoredBytes) }
            val checkZip = ZipFile(checkZipFile, "old_password".toCharArray())
            val header = checkZip.fileHeaders.first()
            val content = checkZip.getInputStream(header).bufferedReader().readText()
            assertEquals("""{"version":1}""", content)
            checkZip.close()

            val wrongZip = ZipFile(checkZipFile, "new_password".toCharArray())
            assertFailsWith<ZipException> {
                wrongZip.getInputStream(wrongZip.fileHeaders.first()).readBytes()
            }
            wrongZip.close()
            checkZipFile.delete()
        }

    @Test
    fun `reencryptBackups partial publish preserves original backup and returns failure`() =
        runTest {
            val fakeStore = FakeBackupStore()
            val sampleZipFile = File(context.cacheDir, "sample_test_partial.zip")
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
            fakeStore.partialPublish = true

            val testManager =
                buildManager(
                    customStoreFactory =
                        object : BackupStoreFactory {
                            override fun create(customUri: Uri?): BackupStore = fakeStore

                            override fun createDefault(): BackupStore = fakeStore
                        },
                )

            val result = testManager.reencryptBackups("old_password", "new_password")
            assertTrue(result.isFailure, "reencryptBackups should fail when partial publish fails")

            val restoredBytes = fakeStore.files["backup_2026-05-15_100000.zip"]
            assertNotNull(restoredBytes)
            val checkZipFile = File(context.cacheDir, "check_orig_partial.zip").apply { writeBytes(restoredBytes) }
            val checkZip = ZipFile(checkZipFile, "old_password".toCharArray())
            val header = checkZip.fileHeaders.first()
            val content = checkZip.getInputStream(header).bufferedReader().readText()
            assertEquals("""{"version":1}""", content)
            checkZip.close()

            val wrongZip = ZipFile(checkZipFile, "new_password".toCharArray())
            assertFailsWith<ZipException> {
                wrongZip.getInputStream(wrongZip.fileHeaders.first()).readBytes()
            }
            wrongZip.close()
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
                buildManager(
                    customStoreFactory =
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
                buildManager(
                    customStoreFactory =
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

        override suspend fun read(location: BackupLocation): InputStream {
            val name = location.value.removePrefix("fake://")
            val bytes = files[name] ?: error("File not found")
            return bytes.inputStream()
        }

        override suspend fun publish(
            source: File,
            name: String,
        ) {
            lastPublishedSourceLength = source.length()
            if (failPublish) throw IOException("Disk full / rename failed")
            if (partialPublish) throw IOException("SAF stream closed mid-write")
            files[name] = source.readBytes()
        }

        override suspend fun delete(location: BackupLocation) {
            val name = location.value.removePrefix("fake://")
            files.remove(name)
        }

        override suspend fun prune(retentionPeriodMs: Long) {
            // No-op for test fake
        }
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
