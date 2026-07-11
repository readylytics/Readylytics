package app.readylytics.health.ui.sync

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.PermissionStatus
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.HistoricalResyncController
import app.readylytics.health.domain.sync.HistoricalResyncState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hcRepo: HealthConnectRepository
    private lateinit var foregroundSyncController: ForegroundSyncController
    private lateinit var historicalResyncController: HistoricalResyncController
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepository: SelectedDateRepository
    private lateinit var viewModel: SyncViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hcRepo = mockk(relaxed = true)
        every { hcRepo.requiredPermissions } returns emptySet()
        every { hcRepo.allPermissions } returns emptySet()
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Unavailable

        foregroundSyncController = mockk(relaxed = true)
        every { foregroundSyncController.syncCompletedEvent } returns MutableSharedFlow()
        every { foregroundSyncController.isSyncing } returns MutableStateFlow(false)

        historicalResyncController = mockk(relaxed = true)
        every { historicalResyncController.state } returns MutableStateFlow(HistoricalResyncState(false, 0, 0))

        settingsRepo = mockk(relaxed = true)
        every { settingsRepo.userPreferences } returns MutableStateFlow(UserPreferences())

        selectedDateRepository = mockk(relaxed = true)
        viewModel =
            SyncViewModel(
                hcRepo = hcRepo,
                foregroundSyncController = foregroundSyncController,
                historicalResyncController = historicalResyncController,
                settingsRepo = settingsRepo,
                selectedDateRepository = selectedDateRepository,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun validateSyncNecessary_succeeds() {
        val result = viewModel.validateSyncNecessary()
        assertTrue(result.isSuccess, "Sync should be valid")
    }

    @Test
    fun uiStateInitial_isCheckingPermissions() {
        assertIs<SyncUiState.CheckingPermissions>(viewModel.uiState.value)
    }

    @Test
    fun onAppForeground_delegatesToAdvanceTodayIfNeeded() {
        viewModel.onAppForeground()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { selectedDateRepository.advanceTodayIfNeeded() }
    }

    @Test
    fun onAppForeground_doesNotResetToTodayUnconditionally() {
        viewModel.onAppForeground()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 0) { selectedDateRepository.resetToToday() }
    }

    @Test
    fun onPermissionsGranted_triggersImmediateSync_andTransitionsToPermissionsGranted() {
        viewModel.onPermissionsGranted()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { foregroundSyncController.triggerImmediateSync() }
        assertIs<SyncUiState.PermissionsGranted>(viewModel.uiState.value)
    }

    @Test
    fun onPermissionsDenied_keepsNeedsPermissionsState() {
        viewModel.onPermissionsDenied()

        assertIs<SyncUiState.NeedsPermissions>(viewModel.uiState.value)
    }

    @Test
    fun onPermissionsGranted_whenPermissionFailureRecheckIsMissing_returnsNeedsPermissions() {
        coEvery {
            foregroundSyncController.triggerImmediateSync()
        } throws HealthConnectPermissionRevokedException(SecurityException("revoked"))
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Missing(setOf("sleep"))

        viewModel.onPermissionsGranted()
        testDispatcher.scheduler.advanceUntilIdle()

        assertIs<SyncUiState.NeedsPermissions>(viewModel.uiState.value)
    }

    @Test
    fun onPermissionsGranted_whenPermissionFailureRecheckIsUnavailable_returnsUnavailable() {
        coEvery {
            foregroundSyncController.triggerImmediateSync()
        } throws HealthConnectPermissionRevokedException(SecurityException("provider unavailable"))
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Unavailable

        viewModel.onPermissionsGranted()
        testDispatcher.scheduler.advanceUntilIdle()

        assertIs<SyncUiState.Unavailable>(viewModel.uiState.value)
    }

    @Test
    fun onPermissionsGranted_whenPermissionFailureRecheckIsGranted_returnsError() {
        coEvery {
            foregroundSyncController.triggerImmediateSync()
        } throws HealthConnectPermissionRevokedException(SecurityException("record read rejected"))
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Granted

        viewModel.onPermissionsGranted()
        testDispatcher.scheduler.advanceUntilIdle()

        assertIs<SyncUiState.Error>(viewModel.uiState.value)
    }

    @Test
    fun onPermissionsGranted_whenPermissionRecheckThrows_returnsError() {
        coEvery {
            foregroundSyncController.triggerImmediateSync()
        } throws HealthConnectPermissionRevokedException(SecurityException("record read rejected"))
        coEvery { hcRepo.checkPermissions() } throws IllegalStateException("provider unavailable")

        viewModel.onPermissionsGranted()
        testDispatcher.scheduler.advanceUntilIdle()

        assertIs<SyncUiState.Error>(viewModel.uiState.value)
    }

    @Test
    fun onAppForeground_withMissingPermissions_routesToNeedsPermissions() {
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Missing(setOf("sleep"))

        viewModel.onAppForeground()
        testDispatcher.scheduler.advanceUntilIdle()

        assertIs<SyncUiState.NeedsPermissions>(viewModel.uiState.value)
    }

    @Test
    fun onAppForeground_withGrantedPermissions_evaluatesSync() {
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Granted

        viewModel.onAppForeground()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { foregroundSyncController.evaluateAndSync() }
        assertIs<SyncUiState.PermissionsGranted>(viewModel.uiState.value)
    }

    @Test
    fun onAppForeground_advancesDateBeforeSyncWhenPermissionsGranted() {
        viewModel.onPermissionsGranted()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onAppForeground()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            selectedDateRepository.advanceTodayIfNeeded()
            foregroundSyncController.evaluateAndSync()
        }
    }

    @Test
    fun onAppForeground_whileCheckIsActive_reusesExistingJob() {
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Granted
        coEvery { foregroundSyncController.evaluateAndSync() } coAnswers { awaitCancellation() }

        viewModel.onAppForeground()
        testDispatcher.scheduler.runCurrent()
        viewModel.onAppForeground()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { foregroundSyncController.evaluateAndSync() }
    }

    @Test
    fun onAppForeground_afterCheckCompletes_allowsNextEvaluation() {
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Granted

        viewModel.onAppForeground()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAppForeground()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { foregroundSyncController.evaluateAndSync() }
    }

    @Test
    fun skipSync_updatesStateToPermissionsGranted() {
        viewModel.skipSync()
        assertIs<SyncUiState.PermissionsGranted>(viewModel.uiState.value)
    }
}
