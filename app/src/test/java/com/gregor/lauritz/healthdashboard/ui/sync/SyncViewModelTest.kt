package com.gregor.lauritz.healthdashboard.ui.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val foregroundSyncController = mockk<ForegroundSyncController>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val selectedDateRepository = mockk<SelectedDateRepository>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: SyncViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { settingsRepo.userPreferences } returns MutableStateFlow(mockk(relaxed = true))
        coEvery { foregroundSyncController.syncCompletedEvent } returns MutableSharedFlow()
        coEvery { foregroundSyncController.isSyncing } returns MutableStateFlow(false)
        coEvery { selectedDateRepository.selectedDate } returns MutableStateFlow(mockk(relaxed = true))
        viewModel = SyncViewModel(hcRepo, foregroundSyncController, settingsRepo, selectedDateRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun syncViewModel_initialState_isCheckingPermissions() {
        assertEquals(SyncUiState.CheckingPermissions, viewModel.uiState.value)
    }

    @Test
    fun syncViewModel_permissionsMissing_needsPermissionsState() {
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Missing(setOf("perm1"))

        viewModel.onAppForeground()

        assertEquals(SyncUiState.NeedsPermissions, viewModel.uiState.value)
    }

    @Test
    fun syncViewModel_permissionsGranted_permissionsGrantedState() {
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Granted

        viewModel.onAppForeground()

        assertEquals(SyncUiState.PermissionsGranted, viewModel.uiState.value)
    }

    @Test
    fun syncViewModel_healthConnectUnavailable_unavailableState() {
        coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Unavailable

        viewModel.onAppForeground()

        assertEquals(SyncUiState.Unavailable, viewModel.uiState.value)
    }

    @Test
    fun syncViewModel_onPermissionsGranted_permissionsGranted() {
        coEvery { foregroundSyncController.triggerImmediateSync() } returns Unit

        viewModel.onPermissionsGranted()

        // UnconfinedTestDispatcher causes immediate transitions: SyncingCatchUp -> PermissionsGranted
        assertEquals(SyncUiState.PermissionsGranted, viewModel.uiState.value)
    }

    @Test
    fun syncViewModel_onAppForeground_resetsDateWhenChanged() =
        runTest {
            val yesterday = LocalDate.now().minusDays(1)
            val dateFlow = MutableStateFlow(yesterday)
            coEvery { selectedDateRepository.selectedDate } returns dateFlow
            coEvery { selectedDateRepository.resetToToday() } returns Unit
            coEvery { hcRepo.checkPermissions() } returns PermissionStatus.Granted

            viewModel.onAppForeground()

            coVerify { selectedDateRepository.resetToToday() }
        }

    @Test
    fun syncViewModel_onAppForeground_skipsSyncWhenAlreadySyncing() =
        runTest {
            val dateFlow = MutableStateFlow(LocalDate.now())
            coEvery { selectedDateRepository.selectedDate } returns dateFlow
            coEvery { selectedDateRepository.resetToToday() } returns Unit
            coEvery { foregroundSyncController.evaluateAndSync() } returns Unit

            viewModel.onAppForeground()

            // Second call should return early due to SyncingCatchUp state
            viewModel.onAppForeground()

            // Should not crash or double-sync
            assertEquals(SyncUiState.SyncingCatchUp, viewModel.uiState.value)
        }
}
