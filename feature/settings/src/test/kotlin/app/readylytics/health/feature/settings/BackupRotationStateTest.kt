package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.backup.BackupOperationPhase
import app.readylytics.health.core.model.domain.backup.BackupOperationState
import app.readylytics.health.core.model.domain.backup.BackupService
import app.readylytics.health.core.model.domain.backup.RestoreService
import app.readylytics.health.core.model.domain.preferences.BackupSettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRotationStateTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var backupSettings: BackupSettings
    private lateinit var backupService: BackupService
    private lateinit var restoreService: RestoreService
    private lateinit var encryptionManager: EncryptionManager
    private val preferencesFlow = MutableStateFlow(UserPreferences(backupPasswordHash = "old_enc_hash"))
    private val operationStateFlow = MutableStateFlow(BackupOperationState())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settingsRepo =
            mockk {
                every { userPreferences } returns preferencesFlow
            }
        backupSettings = mockk(relaxed = true)
        backupService =
            mockk(relaxed = true) {
                every { operationState } returns operationStateFlow
                coEvery { listBackups() } returns emptyList()
                coEvery { rotatePassword(any()) } returns Result.success(Unit)
                coEvery { createBackup() } returns Result.success(Unit)
            }
        restoreService = mockk(relaxed = true)
        encryptionManager =
            mockk {
                every { decrypt("old_enc_hash") } returns "old_password"
                every { encrypt("new_password") } returns "new_enc_hash"
            }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LocalBackupViewModel =
        LocalBackupViewModel(
            settingsRepo = settingsRepo,
            backupSettings = backupSettings,
            backupService = backupService,
            restoreService = restoreService,
            encryptionManager = encryptionManager,
            ioDispatcher = testDispatcher,
        )

    @Test
    fun updateBackupPassword_delegatesToBackupServiceRotatePassword() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onEvent(SettingsEvent.UpdateBackupPassword(raw = "new_password", autoStartBackup = false))
            advanceUntilIdle()

            coVerify(exactly = 1) { backupService.rotatePassword("new_password") }
            coVerify(exactly = 0) { backupSettings.updateBackupPasswordHash(any()) }
            assertFalse(viewModel.uiState.value.isReencrypting)
            assertEquals(null, viewModel.uiState.value.backupError)

            collectJob.cancel()
        }

    @Test
    fun updateBackupPassword_onFailure_setsBackupErrorWithoutUpdatingHash() =
        runTest(testDispatcher) {
            coEvery { backupService.rotatePassword("new_password") } returns
                Result.failure(IOException("Store write failed"))

            val viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onEvent(SettingsEvent.UpdateBackupPassword(raw = "new_password", autoStartBackup = false))
            advanceUntilIdle()

            coVerify(exactly = 1) { backupService.rotatePassword("new_password") }
            coVerify(exactly = 0) { backupSettings.updateBackupPasswordHash(any()) }
            assertEquals(
                UiText.StringRes(R.string.error_backup_reencrypt_failed),
                viewModel.uiState.value.backupError,
            )
            assertFalse(viewModel.uiState.value.isReencrypting)

            collectJob.cancel()
        }

    @Test
    fun updateBackupPassword_autoStartBackup_createsBackupOnSuccess() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.onEvent(SettingsEvent.UpdateBackupPassword(raw = "new_password", autoStartBackup = true))
            advanceUntilIdle()

            coVerify(exactly = 1) { backupService.rotatePassword("new_password") }
            coVerify(exactly = 1) { backupService.createBackup() }

            collectJob.cancel()
        }

    @Test
    fun operationState_reflectsActivePhaseAndControlsReencryptingStatus() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isReencrypting)
            assertEquals(BackupOperationPhase.IDLE, viewModel.uiState.value.operationState.phase)

            operationStateFlow.value =
                BackupOperationState(
                    operationId = "rot_1",
                    phase = BackupOperationPhase.PREPARING,
                    completedArchives = 0,
                    totalArchives = 2,
                )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isReencrypting)
            assertEquals(BackupOperationPhase.PREPARING, viewModel.uiState.value.operationState.phase)
            assertEquals(2, viewModel.uiState.value.operationState.totalArchives)

            operationStateFlow.value =
                BackupOperationState(
                    operationId = "rot_1",
                    phase = BackupOperationPhase.PUBLISHING,
                    completedArchives = 1,
                    totalArchives = 2,
                )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isReencrypting)
            assertEquals(BackupOperationPhase.PUBLISHING, viewModel.uiState.value.operationState.phase)

            operationStateFlow.value =
                BackupOperationState(
                    operationId = "rot_1",
                    phase = BackupOperationPhase.COMPLETE,
                    completedArchives = 2,
                    totalArchives = 2,
                )
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isReencrypting)
            assertEquals(BackupOperationPhase.COMPLETE, viewModel.uiState.value.operationState.phase)

            collectJob.cancel()
        }
}
