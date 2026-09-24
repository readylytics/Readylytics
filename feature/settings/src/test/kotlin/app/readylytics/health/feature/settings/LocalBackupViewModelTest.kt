package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.backup.BackupFileRef
import app.readylytics.health.core.model.domain.backup.BackupLocation
import app.readylytics.health.core.model.domain.backup.BackupOperationState
import app.readylytics.health.core.model.domain.backup.BackupService
import app.readylytics.health.core.model.domain.backup.RestoreService
import app.readylytics.health.core.model.domain.preferences.BackupSettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.security.EncryptionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalBackupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepo: UserPreferencesReader = mockk(relaxed = true)
    private val backupSettings: BackupSettings = mockk(relaxed = true)
    private val backupService: BackupService = mockk(relaxed = true)
    private val restoreService: RestoreService = mockk(relaxed = true)
    private val encryptionManager: EncryptionManager = mockk(relaxed = true)
    private val prefsFlow = MutableStateFlow(UserPreferences(backupDirectoryUri = "content://dirA"))

    private lateinit var viewModel: LocalBackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepo.userPreferences } returns prefsFlow
        every { backupService.operationState } returns MutableStateFlow(BackupOperationState())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel() =
        LocalBackupViewModel(
            settingsRepo = settingsRepo,
            backupSettings = backupSettings,
            backupService = backupService,
            restoreService = restoreService,
            encryptionManager = encryptionManager,
            ioDispatcher = testDispatcher,
        )

    private fun backup(name: String) = BackupFileRef(name, 0L, 0L, BackupLocation("content://$name"))

    @Test
    fun `changing backup directory refreshes the list without a manual refresh event`() =
        runTest(testDispatcher) {
            coEvery { backupService.listBackups() } returnsMany
                listOf(listOf(backup("a1")), listOf(backup("b1")))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(backup("a1")), viewModel.uiState.value.availableBackups)

            prefsFlow.value = UserPreferences(backupDirectoryUri = "content://dirB")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(backup("b1")), viewModel.uiState.value.availableBackups)

            collectJob.cancel()
        }

    @Test
    fun `switching directory never exposes the previous directory's backups while loading`() =
        runTest(testDispatcher) {
            val dirBResult = CompletableDeferred<List<BackupFileRef>>()
            coEvery { backupService.listBackups() } coAnswers {
                if (prefsFlow.value.backupDirectoryUri == "content://dirA") {
                    listOf(backup("a1"))
                } else {
                    dirBResult.await()
                }
            }

            viewModel = createViewModel()
            val observedStates = mutableListOf<LocalBackupState>()
            val collectJob = launch { viewModel.uiState.collect { observedStates.add(it) } }
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(backup("a1")), viewModel.uiState.value.availableBackups)

            prefsFlow.value = UserPreferences(backupDirectoryUri = "content://dirB")
            testDispatcher.scheduler.runCurrent()

            // Regression pin for UI-001: no emitted frame may ever pair the new directory with
            // the previous directory's backup list -- this is what `listingIsCurrent` prevents.
            assertTrue(
                observedStates.none {
                    it.backupDirectory == "content://dirB" && it.availableBackups.isNotEmpty()
                },
            )
            assertTrue(viewModel.uiState.value.isLoadingBackups)
            assertEquals(emptyList<BackupFileRef>(), viewModel.uiState.value.availableBackups)

            dirBResult.complete(listOf(backup("b1")))
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(backup("b1")), viewModel.uiState.value.availableBackups)
            assertTrue(!viewModel.uiState.value.isLoadingBackups)

            collectJob.cancel()
        }

    @Test
    fun `deleting a backup still forces a refetch within the same directory`() =
        runTest(testDispatcher) {
            coEvery { backupService.listBackups() } returnsMany
                listOf(listOf(backup("a1")), emptyList())
            coEvery { backupService.deleteBackup(any()) } returns Result.success(Unit)

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(backup("a1")), viewModel.uiState.value.availableBackups)

            viewModel.onEvent(SettingsEvent.DeleteLocalBackup(backup("a1")))
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(emptyList<BackupFileRef>(), viewModel.uiState.value.availableBackups)

            collectJob.cancel()
        }
}
