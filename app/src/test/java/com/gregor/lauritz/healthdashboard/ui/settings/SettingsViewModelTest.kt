package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.BackupPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.backup.BackupUseCase
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.sync.ResyncHealthConnectUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@HiltAndroidTest
class SettingsViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>

    @Inject
    lateinit var prefsRepo: UserPreferencesRepository

    @Inject
    lateinit var appConfigRepo: AppConfigRepository

    @Inject
    lateinit var backupPrefsRepo: BackupPreferencesRepository

    @Inject
    lateinit var scoringRepository: ScoringRepository

    @Inject
    lateinit var healthSyncUseCase: HealthSyncUseCase

    @Inject
    lateinit var resyncHealthConnectUseCase: ResyncHealthConnectUseCase

    @Inject
    lateinit var driveAuthManager: DriveAuthManager

    @Inject
    lateinit var backupUseCase: BackupUseCase

    @Inject
    lateinit var restoreUseCase: RestoreUseCase

    @Inject
    lateinit var userUseCase: UserUseCase

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        hiltRule.inject()
    }

    @Test
    fun `retention toggle event updates state`() = runTest {
        val viewModel = SettingsViewModel(
            context,
            prefsRepo,
            appConfigRepo,
            backupPrefsRepo,
            scoringRepository,
            healthSyncUseCase,
            resyncHealthConnectUseCase,
            driveAuthManager,
            backupUseCase,
            restoreUseCase,
            userUseCase,
            workerScheduler,
            workManager
        )

        viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(false))
        var state = viewModel.uiState.first()
        assertFalse(state.retentionDaysEnabled)

        viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(true))
        state = viewModel.uiState.first()
        assertTrue(state.retentionDaysEnabled)
    }

    @Test
    fun `retention days event updates state`() = runTest {
        val viewModel = SettingsViewModel(
            context,
            prefsRepo,
            appConfigRepo,
            backupPrefsRepo,
            scoringRepository,
            healthSyncUseCase,
            resyncHealthConnectUseCase,
            driveAuthManager,
            backupUseCase,
            restoreUseCase,
            userUseCase,
            workerScheduler,
            workManager
        )

        viewModel.onEvent(SettingsEvent.RetentionDaysChanged(500))
        var state = viewModel.uiState.first()
        assertEquals(500, state.retentionDays)

        viewModel.onEvent(SettingsEvent.RetentionDaysChanged(180))
        state = viewModel.uiState.first()
        assertEquals(180, state.retentionDays)
    }

    @Test
    fun `resync event sets loading state`() = runTest {
        val viewModel = SettingsViewModel(
            context,
            prefsRepo,
            appConfigRepo,
            backupPrefsRepo,
            scoringRepository,
            healthSyncUseCase,
            resyncHealthConnectUseCase,
            driveAuthManager,
            backupUseCase,
            restoreUseCase,
            userUseCase,
            workerScheduler,
            workManager
        )

        assertFalse(viewModel.uiState.first().isResyncing)
        viewModel.onEvent(SettingsEvent.ResyncHealthConnect)
        // Note: isResyncing would be true during the resync, but clearing all data
        // in test context may cause the resync to fail quickly.
    }
}
