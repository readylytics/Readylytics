package com.gregor.lauritz.healthdashboard.ui.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hcRepo: HealthConnectRepository
    private lateinit var foregroundSyncController: ForegroundSyncController
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepository: SelectedDateRepository
    private lateinit var viewModel: SyncViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hcRepo = mockk(relaxed = true)
        every { hcRepo.requiredPermissions } returns emptySet()
        every { hcRepo.allPermissions } returns emptySet()

        foregroundSyncController = mockk(relaxed = true)
        every { foregroundSyncController.syncCompletedEvent } returns MutableSharedFlow()
        every { foregroundSyncController.isSyncing } returns MutableStateFlow(false)

        settingsRepo = mockk(relaxed = true)
        every { settingsRepo.userPreferences } returns MutableStateFlow(UserPreferences())

        selectedDateRepository = mockk(relaxed = true)
        viewModel =
            SyncViewModel(
                hcRepo = hcRepo,
                foregroundSyncController = foregroundSyncController,
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
        assert(result.isSuccess) { "Sync should be valid" }
    }

    @Test
    fun validateSync_isIdempotent() {
        val result1 = viewModel.validateSyncNecessary()
        val result2 = viewModel.validateSyncNecessary()
        assert(result1.isSuccess && result2.isSuccess) { "Multiple calls should succeed" }
    }

    @Test
    fun uiStateInitial_isCheckingPermissions() {
        assert(viewModel.uiState.value is SyncUiState.CheckingPermissions)
    }

    @Test
    fun syncEvents_flowExists() {
        assert(viewModel.syncEvents != null) { "Sync events flow should exist" }
    }

    @Test
    fun requiredPermissions_flowExists() {
        assert(viewModel.requiredPermissions != null) { "Required permissions should exist" }
    }

    @Test
    fun isSyncing_flowExists() {
        assert(viewModel.isSyncing != null) { "isSyncing flow should exist" }
    }
}
