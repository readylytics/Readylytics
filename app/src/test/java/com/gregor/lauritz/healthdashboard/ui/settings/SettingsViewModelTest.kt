package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.BackupPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.backup.BackupUseCase
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.sync.ResyncHealthConnectUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import androidx.work.WorkManager

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class SettingsViewModelTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context

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

    @Inject
    lateinit var circadianThresholdPreferences: CircadianThresholdPreferences

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize WorkManager for tests since it's disabled in manifest
        try {
            val config = androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
            androidx.work.WorkManager.initialize(context, config)
        } catch (e: IllegalStateException) {
            // Already initialized
        }

        hiltRule.inject()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
            workManager,
            circadianThresholdPreferences
        )

        // Wait for initial load
        viewModel.uiState.first { !it.ui.isLoading }

        viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(false))
        
        // Wait for state to update
        val state = viewModel.uiState.first { !it.retentionDaysEnabled }
        assertFalse(state.retentionDaysEnabled)

        viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(true))
        val state2 = viewModel.uiState.first { it.retentionDaysEnabled }
        assertTrue(state2.retentionDaysEnabled)
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
            workManager,
            circadianThresholdPreferences
        )

        // Wait for initial load
        viewModel.uiState.first { !it.ui.isLoading }

        viewModel.onEvent(SettingsEvent.RetentionDaysChanged(500))
        val state = viewModel.uiState.first { it.retentionDays == 500 }
        assertEquals(500, state.retentionDays)

        viewModel.onEvent(SettingsEvent.RetentionDaysChanged(180))
        val state2 = viewModel.uiState.first { it.retentionDays == 180 }
        assertEquals(180, state2.retentionDays)
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
            workManager,
            circadianThresholdPreferences
        )

        // Wait for initial load
        viewModel.uiState.first { !it.ui.isLoading }

        assertFalse(viewModel.uiState.value.ui.isResyncing)
        viewModel.onEvent(SettingsEvent.ResyncHealthConnect)
        
        val state = viewModel.uiState.first { it.ui.isResyncing }
        assertTrue(state.ui.isResyncing)
    }
}
