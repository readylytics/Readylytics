package app.readylytics.health.workers

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.data.migration.ExistingDatabaseState
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.model.domain.migration.DatabaseMigrationFailureKind
import app.readylytics.health.core.model.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.V7MigrationPhase
import app.readylytics.health.core.model.domain.migration.V7MigrationResult
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.migration.DatabasePreparationRunner
import app.readylytics.health.domain.migration.DatabaseMigrationControllerImpl
import com.google.common.util.concurrent.Futures
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationWorkerTest {
    private lateinit var context: Context
    private lateinit var params: WorkerParameters
    private val migrator = mockk<DatabasePreparationRunner>()
    private val foregroundUpdater = mockk<androidx.work.ForegroundUpdater>()
    private val progressUpdater = mockk<androidx.work.ProgressUpdater>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        params = mockk(relaxed = true)
        every { params.taskExecutor } returns mockk(relaxed = true)
        every { params.foregroundUpdater } returns foregroundUpdater
        every { params.progressUpdater } returns progressUpdater
        every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } returns
            Futures.immediateFuture(null)
        every { progressUpdater.updateProgress(any(), any(), any()) } returns
            Futures.immediateFuture(null)
    }

    @Test
    fun `getForegroundInfo uses distinct migration notification and data sync type`() =
        runBlocking {
            val info: ForegroundInfo = worker().getForegroundInfo()

            assertEquals(SyncNotifications.DATABASE_MIGRATION_NOTIFICATION_ID, info.notificationId)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
            assertEquals(SyncNotifications.DATABASE_MIGRATION_CHANNEL_ID, info.notification.channelId)
        }

    @Test
    fun `progress and failure output keys stay stable`() {
        assertEquals("phase", DatabaseMigrationWorker.KEY_PHASE)
        assertEquals("copiedRows", DatabaseMigrationWorker.KEY_COPIED_ROWS)
        assertEquals("totalRows", DatabaseMigrationWorker.KEY_TOTAL_ROWS)
        assertEquals("requiredBytes", DatabaseMigrationWorker.KEY_REQUIRED_BYTES)
        assertEquals("availableBytes", DatabaseMigrationWorker.KEY_AVAILABLE_BYTES)
    }

    @Test
    fun `foreground starts before migration and progress updates foreground plus work data`() =
        runBlocking {
            val progress =
                DatabaseMigrationProgress(
                    phase = V7MigrationPhase.COPY_HEART_RATE,
                    copiedRows = 12L,
                    totalRows = 50L,
                )
            coEvery { migrator.run(any()) } coAnswers {
                firstArg<suspend (DatabaseMigrationProgress) -> Unit>().invoke(progress)
                V7MigrationResult.Complete
            }

            val result = worker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerifyOrder {
                foregroundUpdater.setForegroundAsync(any(), any(), any())
                migrator.run(any())
            }
            coVerify {
                progressUpdater.updateProgress(
                    any(),
                    any(),
                    match {
                        it.getString(DatabaseMigrationWorker.KEY_PHASE) == progress.phase.name &&
                            it.getLong(DatabaseMigrationWorker.KEY_COPIED_ROWS, -1L) == 12L &&
                            it.getLong(DatabaseMigrationWorker.KEY_TOTAL_ROWS, -1L) == 50L
                    },
                )
            }
            coVerify(exactly = 2) { foregroundUpdater.setForegroundAsync(any(), any(), any()) }
        }

    @Test
    fun `insufficient space returns failure with required and available bytes`() =
        runBlocking {
            coEvery { migrator.run(any()) } returns V7MigrationResult.InsufficientSpace(900L, 400L)

            val result = worker().doWork()

            assertEquals(
                ListenableWorker.Result.failure(
                    Data
                        .Builder()
                        .putLong(DatabaseMigrationWorker.KEY_REQUIRED_BYTES, 900L)
                        .putLong(DatabaseMigrationWorker.KEY_AVAILABLE_BYTES, 400L)
                        .build(),
                ),
                result,
            )
        }

    @Test
    fun `permanent plaintext failures publish terminal typed output instead of retry`() =
        runBlocking {
            for (kind in listOf(
                DatabaseMigrationFailureKind.UNSUPPORTED_VERSION,
                DatabaseMigrationFailureKind.KEY_CORRUPTED,
            )) {
                coEvery { migrator.run(any()) } returns V7MigrationResult.Failed("permanent", kind)
                assertEquals(
                    ListenableWorker.Result.failure(
                        Data
                            .Builder()
                            .putString(DatabaseMigrationWorker.KEY_FAILURE_KIND, kind.name)
                            .build(),
                    ),
                    worker().doWork(),
                )
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `plaintext runner failures flow through worker to terminal controller UI and stale Ready`() =
        runTest {
            for (version in listOf(24, 23)) {
                val file = File.createTempFile("worker-preparation", ".db")
                try {
                    file.writeBytes("SQLite format 3\u0000".encodeToByteArray())
                    val gate = DatabaseReadinessGate(file) { ExistingDatabaseState(23, false) }
                    val dispatcher = StandardTestDispatcher(testScheduler)
                    val result = permanentPreparationFailure(file, gate, version, dispatcher)
                    val workInfos = MutableStateFlow(listOf(failedWork(result.outputData)))
                    val workManager =
                        mockk<WorkManager> {
                            every {
                                getWorkInfosForUniqueWorkFlow(
                                    WorkerScheduler.DATABASE_MIGRATION_WORK_NAME,
                                )
                            } returns
                                workInfos
                        }
                    val controller =
                        DatabaseMigrationControllerImpl(
                            mockk(relaxed = true),
                            workManager,
                            gate,
                            backgroundScope,
                            dispatcher,
                        )
                    advanceUntilIdle()
                    // backgroundScope uses a background dispatcher; runCurrent includes its pending collection.
                    runCurrent()
                    val expected =
                        when (version) {
                            24 -> DatabaseReadiness.Failed("Database migration failed")
                            else -> DatabaseReadiness.KeyCorrupted
                        }
                    assertEquals(expected, controller.state.value.readiness)
                    assertEquals("SQLite format 3\u0000", file.readText())
                    file.writeText("encrypted fixture marker")
                    workInfos.value = listOf(failedWork(result.outputData))
                    runCurrent()
                    assertEquals(DatabaseReadiness.Ready, controller.state.value.readiness)
                } finally {
                    file.delete()
                }
            }
        }

    private suspend fun permanentPreparationFailure(
        file: File,
        gate: DatabaseReadinessGate,
        version: Int,
        dispatcher: CoroutineDispatcher,
    ): ListenableWorker.Result.Failure {
        val keyManager = mockk<SqlCipherKeyManager>(relaxed = true)
        every { keyManager.migrateIfNeeded(file, any()) } throws
            SqlCipherKeyManager.KeyDecryptionException("corrupt")
        val preparation =
            DatabasePreparationRunner(
                file,
                gate,
                keyManager,
                Lazy { error("must not resolve v7 for permanent preparation failure") },
                dispatcher,
                { Long.MAX_VALUE },
                { version },
            )
        return DatabaseMigrationWorker(context, params, preparation).doWork() as ListenableWorker.Result.Failure
    }

    private fun failedWork(output: Data): WorkInfo =
        mockk {
            every { state } returns WorkInfo.State.FAILED
            every { outputData } returns output
        }

    @Test
    fun `ordinary migration failure retries`() =
        runBlocking {
            coEvery { migrator.run(any()) } returns V7MigrationResult.Failed("validation")

            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        }

    @Test
    fun `unexpected exception retries`() =
        runBlocking {
            coEvery { migrator.run(any()) } throws IllegalStateException("io")

            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        }

    @Test
    fun `cancellation is rethrown`() {
        runBlocking {
            coEvery { migrator.run(any()) } throws CancellationException("stop")

            assertFailsWith<CancellationException> { worker().doWork() }
        }
    }

    private fun worker() = DatabaseMigrationWorker(context, params, migrator)
}
