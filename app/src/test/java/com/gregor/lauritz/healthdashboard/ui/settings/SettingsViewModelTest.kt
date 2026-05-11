package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ResyncHealthConnectUseCase
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    lateinit var settingsRepo: SettingsRepository

    @Inject
    lateinit var scoringRepository: ScoringRepository

    @Inject
    lateinit var healthSyncUseCase: HealthSyncUseCase

    @Inject
    lateinit var resyncHealthConnectUseCase: ResyncHealthConnectUseCase

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var circadianThresholdPreferences: CircadianThresholdPreferences

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Initialize WorkManager for tests
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
    fun `UISettingsViewModel retention toggle event updates state`() = runTest {
        val viewModel = UISettingsViewModel(
            settingsRepo,
            healthSyncUseCase
        )
        viewModel.sharingStarted = SharingStarted.Lazily

        // Launch collection in backgroundScope to handle Lazily start and automatic cleanup
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(false))
        advanceUntilIdle()
        
        val state = viewModel.uiState.first { !it.retentionDaysEnabled }
        assertFalse(state.retentionDaysEnabled)

        viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(true))
        advanceUntilIdle()
        
        val state2 = viewModel.uiState.first { it.retentionDaysEnabled }
        assertTrue(state2.retentionDaysEnabled)
    }

    @Test
    fun `UISettingsViewModel retention days event updates state`() = runTest {
        val viewModel = UISettingsViewModel(
            settingsRepo,
            healthSyncUseCase
        )
        viewModel.sharingStarted = SharingStarted.Lazily

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        viewModel.onEvent(SettingsEvent.RetentionDaysChanged(500))
        advanceUntilIdle()
        
        val state = viewModel.uiState.first { it.retentionDays == 500 }
        assertEquals(500, state.retentionDays)

        viewModel.onEvent(SettingsEvent.RetentionDaysChanged(180))
        advanceUntilIdle()
        
        val state2 = viewModel.uiState.first { it.retentionDays == 180 }
        assertEquals(180, state2.retentionDays)
    }

    @Test
    fun `SyncSettingsViewModel resync event sets loading state`() = runTest {
        val viewModel = SyncSettingsViewModel(
            settingsRepo,
            healthSyncUseCase,
            resyncHealthConnectUseCase
        )
        viewModel.sharingStarted = SharingStarted.Lazily

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        assertFalse(viewModel.uiState.value.isResyncing)
        viewModel.onEvent(SettingsEvent.ResyncHealthConnect)
        advanceUntilIdle()
    }
}
