package app.readylytics.health.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.HealthMutationCoordinatorImpl
import app.readylytics.health.core.databaseschema.data.local.entity.HealthMutationStateEntity
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SyncPreference
import app.readylytics.health.core.model.domain.audit.AuditEvent
import app.readylytics.health.core.model.domain.audit.AuditTrailRepository
import app.readylytics.health.core.model.domain.backup.ArchiveRotationEntry
import app.readylytics.health.core.model.domain.backup.BackupFileInfo
import app.readylytics.health.core.model.domain.backup.BackupInventoryPolicy
import app.readylytics.health.core.model.domain.backup.BackupLocation
import app.readylytics.health.core.model.domain.backup.BackupOperationPhase
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.preferences.BackupSettings
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var backupSettings: BackupSettings
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

        db = Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java).allowMainThreadQueries().build()

        encryptionManager = mockk<EncryptionManager>(relaxed = true)
        every { encryptionManager.encrypt(any()) } answers { "enc_" + firstArg<String>() }
        every { encryptionManager.decrypt(any()) } answers {
            val arg = firstArg<String>()
            when (arg) {
                "hashed_password" -> "old_password"
                "old_pass" -> "old_pass"
                else -> arg.removePrefix("enc_")
            }
        }

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

        backupSettings = mockk(relaxed = true)
        cardConfigRepo = mockk(relaxed = true) { every { dashboardCardConfigurations() } returns flowOf(emptyList()) }
        vitalsLayoutRepo =
            mockk(relaxed = true) {
                every { vitalsCardConfigurations() } returns flowOf(emptyList())
                every { vitalsChartConfigurations() } returns flowOf(emptyList())
            }
        sleepLayoutRepo =
            mockk(relaxed = true) {
                every { sleepTopCardConfigurations() } returns flowOf(emptyList())
                every { sleepChartConfigurations() } returns flowOf(emptyList())
                every { sleepMetricCardConfigurations() } returns flowOf(emptyList())
            }
        workoutsLayoutRepo =
            mockk(relaxed = true) {
                every { workoutCardConfigurations() } returns flowOf(emptyList())
                every { workoutChartConfigurations() } returns flowOf(emptyList())
                every { workoutHistoryConfigurations() } returns flowOf(emptyList())
            }
        workoutDetailLayoutRepo = mockk(relaxed = true) { every { allLayouts() } returns flowOf(emptyMap()) }
        auditTrailRepository = FakeAuditTrailRepository()
        manager = buildManager()
    }

    private fun buildRotationService(
        customSettingsRepo: SettingsRepository = settingsRepo,
        customBackupSettings: BackupSettings = backupSettings,
        customStoreFactory: BackupStoreFactory = DefaultBackupStoreFactory(context),
        customJournal: BackupOperationJournal = BackupOperationJournal(context, encryptionManager),
        customPublisher: VerifiedArchivePublisher = VerifiedArchivePublisher(RestoreInventoryValidator()),
    ): BackupRotationService =
        BackupRotationService(
            context = context,
            userPreferencesReader = customSettingsRepo,
            backupSettings = customBackupSettings,
            encryptionManager = encryptionManager,
            backupStoreFactory = customStoreFactory,
            journal = customJournal,
            verifiedPublisher = customPublisher,
            auditTrailRepository = auditTrailRepository,
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun buildManager(
        customSettingsRepo: SettingsRepository = settingsRepo,
        customBackupSettings: BackupSettings = backupSettings,
        customStoreFactory: BackupStoreFactory = DefaultBackupStoreFactory(context),
        rotationService: BackupRotationService? = null,
    ): LocalBackupManager {
        runBlocking {
            db.healthMutationStateDao().upsert(HealthMutationStateEntity(id = 1, sourceGeneration = 0))
        }
        val coordinator = HealthMutationCoordinatorImpl(db.healthMutationStateDao())
        val layoutRepos =
            RestoreLayoutRepositories(
                cardConfigRepo,
                vitalsLayoutRepo,
                sleepLayoutRepo,
                workoutsLayoutRepo,
                workoutDetailLayoutRepo,
            )
        val exporter =
            BackupSnapshotExporter(db, coordinator, customSettingsRepo, layoutRepos, BackupStreamWriter(db))
        val rotService =
            rotationService ?: buildRotationService(
                customSettingsRepo = customSettingsRepo,
                customBackupSettings = customBackupSettings,
                customStoreFactory = customStoreFactory,
            )
        return LocalBackupManager(
            context,
            customSettingsRepo,
            exporter,
            encryptionManager,
            auditTrailRepository,
            Dispatchers.Unconfined,
            customStoreFactory,
            RestoreInventoryValidator(),
            rotService,
        )
    }

    @After
    fun tearDown() {
        db.close()
        backupDir.deleteRecursively()
        File(context.cacheDir, "backup-rotation").deleteRecursively()
        File(context.filesDir, "backup_rotation_journal.enc").delete()
    }

    @Test
    fun reencryptBackups_preservesSuccessWhenSuccessAuditAppendFails() =
        runTest {
            auditTrailRepository.appendFailure = { event ->
                if (event.type == AuditEvent.Type.KEY_ROTATED) RuntimeException("audit unavailable") else null
            }
            val result = manager.rotatePassword("new_password")
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
            val result = manager.rotatePassword("new_password")
            assertTrue(result.isFailure)
            assertEquals(originalFailure::class, result.exceptionOrNull()!!::class)
        }

    @Test
    fun reencryptBackups_failurePreservesOriginalBackupAndReturnsFailure() =
        runTest {
            val fakeStore = FakeBackupStore(failPublish = true)
            fakeStore.files["backup_2026-05-15_100000.zip"] = createValidZipBytes("old_password")

            val testManager =
                buildManager(
                    customBackupSettings = backupSettings,
                    customStoreFactory = simpleStoreFactory(fakeStore),
                )
            val result = testManager.rotatePassword("new_password")
            assertTrue(result.isFailure, "reencryptBackups should fail when publish fails")

            val originalBytes = fakeStore.files["backup_2026-05-15_100000.zip"]
            assertNotNull(originalBytes)
            assertZipReadableWithPassword(originalBytes, "old_password")
            assertZipFailsWithPassword(originalBytes, "new_password")
            coVerify(exactly = 0) { backupSettings.updateBackupPasswordHash(any()) }
        }

    @Test
    fun reencryptBackups_partialPublishPreservesOriginalBackupAndReturnsFailure() =
        runTest {
            val fakeStore = FakeBackupStore(partialPublish = true)
            fakeStore.files["backup_2026-05-15_100000.zip"] = createValidZipBytes("old_password")

            val testManager =
                buildManager(
                    customBackupSettings = backupSettings,
                    customStoreFactory = simpleStoreFactory(fakeStore),
                )
            val result = testManager.rotatePassword("new_password")
            assertTrue(result.isFailure, "reencryptBackups should fail when partial publish fails")

            val originalBytes = fakeStore.files["backup_2026-05-15_100000.zip"]
            assertNotNull(originalBytes)
            assertZipReadableWithPassword(originalBytes, "old_password")
            assertZipFailsWithPassword(originalBytes, "new_password")
            coVerify(exactly = 0) { backupSettings.updateBackupPasswordHash(any()) }
        }

    @Test
    fun reencryptBackups_verificationFailureDeletesPublishedFileAndPreservesOriginal() =
        runTest {
            val fakeStore = FakeBackupStore()
            fakeStore.files["backup_2026-05-15_100000.zip"] = createValidZipBytes("old_password")

            val failingValidator =
                mockk<RestoreInventoryValidator> {
                    every { validate(any<InputStream>()) } throws IllegalStateException("Corrupt backup content")
                }
            val rotService =
                buildRotationService(
                    customStoreFactory = simpleStoreFactory(fakeStore),
                    customPublisher = VerifiedArchivePublisher(failingValidator),
                )
            val testManager =
                buildManager(
                    customBackupSettings = backupSettings,
                    customStoreFactory = simpleStoreFactory(fakeStore),
                    rotationService = rotService,
                )

            val result = testManager.rotatePassword("new_password")
            assertTrue(result.isFailure, "Must fail when readback validation fails")

            assertEquals(1, fakeStore.files.size)
            val originalBytes = fakeStore.files["backup_2026-05-15_100000.zip"]
            assertNotNull(originalBytes)
            assertZipReadableWithPassword(originalBytes, "old_password")
            coVerify(exactly = 0) { backupSettings.updateBackupPasswordHash(any()) }
        }

    @Test
    fun reencryptBackups_neverCallsPublishWithZeroLengthSource() =
        runTest {
            val fakeStore = FakeBackupStore()
            fakeStore.files["backup_2026-05-15_100000.zip"] = createValidZipBytes("old_password")

            val testManager = buildManager(customStoreFactory = simpleStoreFactory(fakeStore))
            val result = testManager.rotatePassword("new_password")

            assertTrue(result.isSuccess)
            assertTrue(fakeStore.lastPublishedSourceLength > 0, "Source length must be > 0")
        }

    @Test
    fun reencryptBackups_successUpdatesPasswordHashAndReplacesOriginal() =
        runTest {
            val fakeStore = FakeBackupStore()
            fakeStore.files["backup_2026-05-15_100000.zip"] = createValidZipBytes("old_password")

            val testManager =
                buildManager(
                    customBackupSettings = backupSettings,
                    customStoreFactory = simpleStoreFactory(fakeStore),
                )
            val result = testManager.rotatePassword("new_password")

            assertTrue(result.isSuccess)
            coVerify(exactly = 1) { backupSettings.updateBackupPasswordHash("enc_new_password") }
            assertEquals(1, fakeStore.files.size)
            val newEntry = fakeStore.files.entries.single()
            assertTrue(newEntry.key.startsWith("backup_2026-05-15_100000_r"))
            assertZipReadableWithPassword(newEntry.value, "new_password")
            assertZipFailsWithPassword(newEntry.value, "old_password")
        }

    @Test
    fun reencryptBackups_doesNotWritePlaintextJsonFilesToDiskDuringRotation() =
        runTest {
            val fakeStore = FakeBackupStore()
            fakeStore.files["backup_2026-05-15_100000.zip"] = createValidZipBytes("old_password")

            val testManager = buildManager(customStoreFactory = simpleStoreFactory(fakeStore))
            val result = testManager.rotatePassword("new_password")

            assertTrue(result.isSuccess)
            val jsonFiles =
                context.cacheDir
                    .walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".json") }
                    .toList()
            assertTrue(jsonFiles.isEmpty(), "No plaintext JSON files must exist in cacheDir: $jsonFiles")
        }

    @Test
    fun rotatePassword_partialBatchPublishFailureRollsBackAllPublishedArchivesAndResetsState() =
        runTest {
            val fakeStore = FakeBackupStore(failPublishOnNth = 2)
            val orig1 = createValidZipBytes("old_password")
            val orig2 = createValidZipBytes("old_password")
            fakeStore.files["backup_2026-05-15_100000.zip"] = orig1
            fakeStore.files["backup_2026-05-16_100000.zip"] = orig2

            val rotService = buildRotationService(customStoreFactory = simpleStoreFactory(fakeStore))
            val testManager =
                buildManager(
                    customBackupSettings = backupSettings,
                    customStoreFactory = simpleStoreFactory(fakeStore),
                    rotationService = rotService,
                )

            val result = testManager.rotatePassword("new_password")
            assertTrue(result.isFailure, "Rotation must fail when 2nd archive publish fails")

            // Exactly the 2 original files remain; any published archive from the first step was rolled back
            assertEquals(2, fakeStore.files.size)
            assertTrue(fakeStore.files.containsKey("backup_2026-05-15_100000.zip"))
            assertTrue(fakeStore.files.containsKey("backup_2026-05-16_100000.zip"))

            // Original files are readable with old password, fail with new password
            assertZipReadableWithPassword(fakeStore.files["backup_2026-05-15_100000.zip"]!!, "old_password")
            assertZipFailsWithPassword(fakeStore.files["backup_2026-05-15_100000.zip"]!!, "new_password")
            assertZipReadableWithPassword(fakeStore.files["backup_2026-05-16_100000.zip"]!!, "old_password")
            assertZipFailsWithPassword(fakeStore.files["backup_2026-05-16_100000.zip"]!!, "new_password")

            // Password hash was NOT committed
            coVerify(exactly = 0) { backupSettings.updateBackupPasswordHash(any()) }

            // Lifecycle state is reset to IDLE, not stuck in mid-flight phase
            assertEquals(BackupOperationPhase.IDLE, rotService.operationState.value.phase)
        }

    @Test
    fun rotatePassword_singleArchiveFailureResetsStateToIdle() =
        runTest {
            val fakeStore = FakeBackupStore(failPublish = true)
            fakeStore.files["backup_2026-05-15_100000.zip"] = createValidZipBytes("old_password")

            val rotService = buildRotationService(customStoreFactory = simpleStoreFactory(fakeStore))
            val testManager =
                buildManager(
                    customBackupSettings = backupSettings,
                    customStoreFactory = simpleStoreFactory(fakeStore),
                    rotationService = rotService,
                )

            val result = testManager.rotatePassword("new_password")
            assertTrue(result.isFailure)
            assertEquals(BackupOperationPhase.IDLE, rotService.operationState.value.phase)
        }

    @Test
    fun crashRecovery_beforeCredentialCommitted_cleansUpPublishedAndPreservesOriginal() =
        runTest {
            val fakeStore = FakeBackupStore()
            val origBytes = createValidZipBytes("old_password")
            fakeStore.files["backup_orig.zip"] = origBytes
            fakeStore.files["backup_published.zip"] = createValidZipBytes("new_password")

            val journal = BackupOperationJournal(context, encryptionManager)
            val journalData =
                RotationJournalData(
                    operationId = "rot_crash_1",
                    directoryUri = null,
                    phase = BackupOperationPhase.PUBLISHING,
                    encryptedOldPassword = "hashed_password",
                    encryptedNewPassword = "new_hashed_password",
                    targetPasswordHash = "new_hashed_password",
                    entries =
                        listOf(
                            ArchiveRotationEntry(
                                originalLocation = "fake://backup_orig.zip",
                                publishedLocation = "fake://backup_published.zip",
                                verified = true,
                            ),
                        ),
                    selectedGeneration = 1L,
                )
            journal.write(journalData)

            val rotService =
                buildRotationService(customStoreFactory = simpleStoreFactory(fakeStore), customJournal = journal)
            val result = rotService.rotatePassword("new_password")
            if (result.isFailure) {
                throw AssertionError(
                    "rotatePassword failed: ${result.exceptionOrNull()?.message}",
                    result.exceptionOrNull(),
                )
            }

            assertNotNull(
                fakeStore.files.keys.firstOrNull {
                    it.startsWith("backup_orig_r")
                },
                "files in store: ${fakeStore.files.keys}",
            )
            assertTrue(!fakeStore.files.containsKey("backup_published.zip"))
        }

    private fun simpleStoreFactory(store: BackupStore) =
        object : BackupStoreFactory {
            override fun create(customUri: Uri?): BackupStore = store

            override fun createDefault(): BackupStore = store
        }

    private fun createValidZipBytes(password: String?): ByteArray {
        val tempZip = File(context.cacheDir, "temp_make_${System.currentTimeMillis()}.zip")
        val tempJson =
            File(context.cacheDir, "backup.json").apply {
                val root = JSONObject()
                root.put("schemaVersion", HealthDatabase.DATABASE_VERSION)
                root.put("exportedAt", "2026-05-15T10:00:00Z")
                root.put("sourceGeneration", 1L)
                root.put("scoringSnapshotId", "snap-1")
                val rowCounts = JSONObject()
                val required = BackupInventoryPolicy.requiredTables(HealthDatabase.DATABASE_VERSION)
                required.forEach { table ->
                    rowCounts.put(table, 0)
                    root.put(table, JSONArray())
                }
                root.put("rowCounts", rowCounts)
                root.put("preferences", JSONObject())
                writeText(root.toString())
            }

        val zip = ZipFile(tempZip, password?.toCharArray())
        val params =
            ZipParameters().apply {
                fileNameInZip = "backup.json"
                if (password != null) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }
        zip.addFile(tempJson, params)
        zip.close()
        tempJson.delete()
        val bytes = tempZip.readBytes()
        tempZip.delete()
        return bytes
    }

    private fun assertZipReadableWithPassword(
        bytes: ByteArray,
        password: String,
    ) {
        val temp = File(context.cacheDir, "temp_check_${System.currentTimeMillis()}.zip").apply { writeBytes(bytes) }
        val zip = ZipFile(temp, password.toCharArray())
        val header = zip.fileHeaders.first()
        val content = zip.getInputStream(header).bufferedReader().readText()
        assertTrue(content.contains("schemaVersion"))
        zip.close()
        temp.delete()
    }

    private fun assertZipFailsWithPassword(
        bytes: ByteArray,
        password: String,
    ) {
        val temp = File(context.cacheDir, "temp_fail_${System.currentTimeMillis()}.zip").apply { writeBytes(bytes) }
        val zip = ZipFile(temp, password.toCharArray())
        val header = zip.fileHeaders.first()
        assertFailsWith<ZipException> { zip.getInputStream(header).readBytes() }
        zip.close()
        temp.delete()
    }

    class FakeBackupStore(
        var files: MutableMap<String, ByteArray> = mutableMapOf(),
        var failPublish: Boolean = false,
        var partialPublish: Boolean = false,
        var failPublishOnNth: Int? = null,
    ) : BackupStore {
        var lastPublishedSourceLength: Long = -1
        var publishCount: Int = 0

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
            val bytes = files[name] ?: error("File not found: $name")
            return bytes.inputStream()
        }

        override suspend fun publish(
            source: File,
            name: String,
        ) {
            publishNew(source, name)
        }

        override suspend fun publishNew(
            source: File,
            name: String,
        ): BackupLocation {
            lastPublishedSourceLength = source.length()
            publishCount++
            if (failPublish) throw IOException("Disk full / rename failed")
            if (partialPublish) throw IOException("SAF stream closed mid-write")
            val failNth = failPublishOnNth
            if (failNth != null && publishCount >= failNth) {
                throw IOException("Disk full on publish #$publishCount")
            }
            files[name] = source.readBytes()
            return BackupLocation("fake://$name")
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
