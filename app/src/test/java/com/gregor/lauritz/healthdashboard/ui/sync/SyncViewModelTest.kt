package com.gregor.lauritz.healthdashboard.ui.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val foregroundSyncController = mockk<ForegroundSyncController>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: SyncViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { settingsRepo.userPreferences } returns MutableStateFlow(mockk(relaxed = true))
        coEvery { foregroundSyncController.syncCompletedEvent } returns MutableSharedFlow()
        coEvery { foregroundSyncController.isSyncing } returns MutableStateFlow(false)
        viewModel = SyncViewModel(hcRepo, foregroundSyncController, settingsRepo)
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
    fun syncViewModel_onPermissionsGranted_discoveringDevices() {
        coEvery { hcRepo.discoverDevices(any()) } returns listOf("Device 1")

        viewModel.onPermissionsGranted()

        // Note: It might transition quickly to DeviceSelectionReady because of UnconfinedTestDispatcher
        assertTrue(viewModel.uiState.value is SyncUiState.DeviceSelectionReady)
    }
}
