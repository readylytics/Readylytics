package app.readylytics.health.feature.settings.data

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.DeviceSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.sync.HistoricalResyncController
import app.readylytics.health.domain.sync.HistoricalResyncState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataSourceSettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        initialPrefs: UserPreferences = UserPreferences(),
        deviceChangeNoticeDismissed: Boolean = false,
        historicalResyncController: HistoricalResyncController = mockk(relaxed = true),
    ): Triple<DataSourceSettingsViewModel, MutableStateFlow<Map<String, String>>, HistoricalResyncController> {
        val deviceByDataType = MutableStateFlow(initialPrefs.deviceByDataType)
        val prefsFlow =
            MutableStateFlow(
                initialPrefs.copy(
                    deviceChangeNoticeDismissed = deviceChangeNoticeDismissed,
                ),
            )
        val settingsReader =
            mockk<UserPreferencesReader> {
                every { userPreferences } returns prefsFlow
            }
        val deviceSettings =
            mockk<DeviceSettings>(relaxed = true) {
                coEvery { getAvailableDevices() } returns listOf("Watch A", "Watch B")
                coEvery { updateDeviceForDataType(any(), any()) } coAnswers {
                    val key = it.invocation.args[0] as String
                    val value = it.invocation.args[1] as String?
                    deviceByDataType.value =
                        deviceByDataType.value.toMutableMap().apply {
                            if (value.isNullOrBlank()) remove(key) else put(key, value)
                        }
                    prefsFlow.value = prefsFlow.value.copy(deviceByDataType = deviceByDataType.value)
                }
                coEvery { applyDeviceOverrides(any()) } coAnswers {
                    val overrides = it.invocation.args[0] as Map<String, String?>
                    val updated = deviceByDataType.value.toMutableMap()
                    overrides.forEach { (key, value) ->
                        if (value.isNullOrBlank()) updated.remove(key) else updated[key] = value
                    }
                    deviceByDataType.value = updated
                    prefsFlow.value = prefsFlow.value.copy(deviceByDataType = updated)
                }
            }
        every { historicalResyncController.state } returns
            flowOf(
                HistoricalResyncState(running = false, current = 0, total = 0),
            )

        val viewModel = DataSourceSettingsViewModel(settingsReader, deviceSettings, historicalResyncController)
        viewModel.sharingStarted = SharingStarted.Lazily
        return Triple(viewModel, deviceByDataType, historicalResyncController)
    }

    @Test
    fun `changing a device stages the value without persisting`() =
        runTest(testDispatcher) {
            val (viewModel, deviceByDataType, scheduler) = buildViewModel()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()

            assertEquals("Watch A", viewModel.uiState.value.deviceByDataType[HealthDataType.HEART_RATE.name])
            assertTrue(viewModel.uiState.value.hasPendingChanges)
            assertTrue(deviceByDataType.value.isEmpty())
            coVerify(exactly = 0) { scheduler.requestHistoricalResync() }

            job.cancel()
        }

    @Test
    fun `apply persists staged changes and triggers resync`() =
        runTest(testDispatcher) {
            val (viewModel, deviceByDataType, scheduler) = buildViewModel()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()
            viewModel.onApply()
            advanceUntilIdle()

            assertEquals("Watch A", deviceByDataType.value[HealthDataType.HEART_RATE.name])
            assertFalse(viewModel.uiState.value.hasPendingChanges)
            coVerify { scheduler.requestHistoricalResync() }

            job.cancel()
        }

    @Test
    fun `device change notice shows when not dismissed and can be dismissed for this session`() =
        runTest(testDispatcher) {
            val (viewModel, _, _) = buildViewModel(deviceChangeNoticeDismissed = false)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showDeviceChangeNotice)

            viewModel.onNoticeAcknowledged(dismissPermanently = false)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.showDeviceChangeNotice)

            job.cancel()
        }

    @Test
    fun `device change notice stays hidden once permanently dismissed`() =
        runTest(testDispatcher) {
            val (viewModel, _, _) = buildViewModel(deviceChangeNoticeDismissed = true)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.showDeviceChangeNotice)

            job.cancel()
        }

    @Test
    fun `reverting a device back to its persisted value clears pending changes`() =
        runTest(testDispatcher) {
            val initialPrefs = UserPreferences(deviceByDataType = mapOf(HealthDataType.HEART_RATE.name to "Watch A"))
            val (viewModel, _, _) = buildViewModel(initialPrefs = initialPrefs)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch B")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasPendingChanges)

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.hasPendingChanges)

            job.cancel()
        }
}
