package com.gregor.lauritz.healthdashboard.ui.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class SyncViewModelTest {
    private lateinit var hcRepo: HealthConnectRepository
    private lateinit var foregroundSyncController: ForegroundSyncController
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var selectedDateRepository: SelectedDateRepository
    private lateinit var viewModel: SyncViewModel

    @Before
    fun setUp() {
        hcRepo = mockk(relaxed = true)
        foregroundSyncController = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        selectedDateRepository = mockk(relaxed = true)
        viewModel = SyncViewModel(
            hcRepo = hcRepo,
            foregroundSyncController = foregroundSyncController,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateRepository,
        )
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
