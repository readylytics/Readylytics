package app.readylytics.health.data.migration

import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.data.migration.ExistingDatabaseState
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.model.domain.migration.DatabaseMigrationFailureKind
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.V7MigrationResult
import dagger.Lazy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePreparationRunnerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val keyManager =
        mockk<SqlCipherKeyManager> {
            every { discardStalePlaintextExport(any()) } just Runs
        }
    private val migrator = mockk<V7DatabaseMigrator>()
    private val file by lazy { temporaryFolder.newFile("fixture.db") }
    private var version = 6
    private val gate by lazy { DatabaseReadinessGate(file) { ExistingDatabaseState(version, false) } }

    @Test
    fun plaintextV6EncryptsBeforeV7MigrationAndPublishesReady() =
        runTest {
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            every { keyManager.migrateIfNeeded(file, any()) } answers {
                assertEquals(DatabaseReadiness.Checking, gate.readiness.value)
                secondArg<() -> Unit>().invoke()
                file.writeText("encrypted")
            }
            coEvery { migrator.migrate(any()) } coAnswers {
                assertEquals(DatabaseReadiness.MigrationRequired(6), gate.readiness.value)
                version = 7
                V7MigrationResult.Complete
            }
            val runner = runner(StandardTestDispatcher(testScheduler))
            assertEquals(V7MigrationResult.Complete, runner.run {})
            verify(exactly = 1) { keyManager.migrateIfNeeded(file, any()) }
            coVerify(exactly = 1) { migrator.migrate(any()) }
            assertEquals(DatabaseReadiness.Ready, gate.readiness.value)
        }

    @Test
    fun encryptedRoomManagedDatabaseBypassesExportAndMigrator() =
        runTest {
            version = 23
            file.writeText("encrypted")
            assertEquals(V7MigrationResult.Complete, runner(StandardTestDispatcher(testScheduler)).run {})
            verify(exactly = 0) { keyManager.migrateIfNeeded(any(), any()) }
            coVerify(exactly = 0) { migrator.migrate(any()) }
        }

    @Test
    fun missingDatabaseBypassesExportAndMigrator() =
        runTest {
            file.delete()
            assertEquals(V7MigrationResult.Complete, runner(StandardTestDispatcher(testScheduler)).run {})
            verify(exactly = 0) { keyManager.migrateIfNeeded(any(), any()) }
            coVerify(exactly = 0) { migrator.migrate(any()) }
        }

    @Test
    fun insufficientSpaceIncludesWalAndReturnsBeforeExport() =
        runTest {
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            temporaryFolder.newFile("fixture.db-wal").writeBytes(ByteArray(16))
            val result = runner(StandardTestDispatcher(testScheduler), available = 0).run {}
            assertEquals(V7MigrationResult.InsufficientSpace(67_108_904L, 0L), result)
            verify(exactly = 0) { keyManager.migrateIfNeeded(any(), any()) }
        }

    @Test
    fun unsupportedPlaintextVersionsNeverExport() =
        runTest {
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            for (unsupported in listOf(4, 24)) {
                version = unsupported
                assertEquals(
                    DatabaseMigrationFailureKind.UNSUPPORTED_VERSION,
                    assertIs<V7MigrationResult.Failed>(runner(StandardTestDispatcher(testScheduler)).run {}).kind,
                )
            }
            verify(exactly = 0) { keyManager.migrateIfNeeded(any(), any()) }
        }

    @Test
    fun plaintextCorruptKeyProducesPermanentRecoveryFailure() =
        runTest {
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            every { keyManager.migrateIfNeeded(file, any()) } throws
                SqlCipherKeyManager.KeyDecryptionException("corrupt")
            val failure = assertIs<V7MigrationResult.Failed>(runner(StandardTestDispatcher(testScheduler)).run {})
            assertEquals(DatabaseMigrationFailureKind.KEY_CORRUPTED, failure.kind)
            assertEquals("SQLite format 3\u0000", file.readText())
        }

    @Test
    fun spacePreflightRemeasuresAfterReclaimingOnlyAbandonedTarget() =
        runTest {
            version = 23
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            val wal = temporaryFolder.newFile("fixture.db-wal").apply { writeText("committed original wal") }
            val stale =
                temporaryFolder
                    .newFile(
                        "fixture.db.cipher_tmp",
                    ).apply { writeText("allocated abandoned export") }
            val space: (java.io.File) -> Long = { if (stale.exists()) 0L else Long.MAX_VALUE }
            assertEquals(0L, space(file))
            every { keyManager.discardStalePlaintextExport(file) } answers {
                check(stale.delete()) { "test stale export was not reclaimed" }
            }
            every { keyManager.migrateIfNeeded(file, any()) } answers { file.writeText("encrypted") }
            assertEquals(
                V7MigrationResult.Complete,
                runner(StandardTestDispatcher(testScheduler), spaceReader = space).run {},
            )
            assertEquals("committed original wal", wal.readText())
        }

    @Test
    fun cutoverCancellationEscapesAndLeavesSourcePlaintext() =
        runTest {
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            every { keyManager.migrateIfNeeded(file, any()) } throws CancellationException("cutover")
            assertFailsWith<CancellationException> { runner(StandardTestDispatcher(testScheduler)).run {} }
            assertEquals("SQLite format 3\u0000", file.readText())
        }

    @Test
    fun cancelledContextPreventsCutoverWhenCallbackChecksActivity() =
        runTest {
            file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
            val preparationJob = Job(currentCoroutineContext()[Job])
            every { keyManager.migrateIfNeeded(file, any()) } answers {
                preparationJob.cancel()
                secondArg<() -> Unit>().invoke()
                file.writeText("encrypted")
            }
            assertFailsWith<CancellationException> {
                withContext(preparationJob) { runner(StandardTestDispatcher(testScheduler)).run {} }
            }
            assertEquals("SQLite format 3\u0000", file.readText())
        }

    @Test
    fun unsuccessfulReinspectionCannotReportComplete() =
        runTest {
            file.writeText("encrypted")
            coEvery { migrator.migrate(any()) } returns V7MigrationResult.Complete
            assertIs<V7MigrationResult.Failed>(runner(StandardTestDispatcher(testScheduler)).run {})
            assertEquals(DatabaseReadiness.MigrationRequired(6), gate.readiness.value)
        }

    private fun runner(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        available: Long = Long.MAX_VALUE,
        spaceReader: (java.io.File) -> Long = { available },
    ) = DatabasePreparationRunner(file, gate, keyManager, Lazy { migrator }, dispatcher, spaceReader, { version })
}
