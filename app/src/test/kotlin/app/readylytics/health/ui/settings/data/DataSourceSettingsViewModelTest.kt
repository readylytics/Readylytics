package app.readylytics.health.ui.settings.data

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.preferences.UserPreferencesProto
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.workers.WorkerScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataSourceSettingsViewModelTest {
    private fun buildViewModel(
        initialPrefs: UserPreferences = UserPreferences(),
        deviceChangeNoticeDismissed: Boolean = false,
        scheduler: WorkerScheduler = mockk(relaxed = true),
    ): Triple<DataSourceSettingsViewModel, MutableStateFlow<Map<String, String>>, WorkerScheduler> {
        val deviceByDataType = MutableStateFlow(initialPrefs.deviceByDataType)
        val settingsRepo =
            mockk<SettingsRepository>(relaxed = true) {
                every { this@mockk.deviceByDataType } returns deviceByDataType
                every { this@mockk.deviceChangeNoticeDismissed } returns flowOf(deviceChangeNoticeDismissed)
                coEvery { getAvailableDevices() } returns listOf("Watch A", "Watch B")
                coEvery { batchUpdate(any()) } coAnswers {
                    @Suppress("UNCHECKED_CAST")
                    val block = it.invocation.args[0] as UserPreferencesProto.Builder.() -> Unit
                    val builder = UserPreferencesProto.newBuilder()
                    deviceByDataType.value.forEach { (key, value) -> builder.putDeviceByDataType(key, value) }
                    builder.block()
                    deviceByDataType.value = builder.deviceByDataTypeMap.toMap()
                }
            }
        val workManager =
            mockk<androidx.work.WorkManager> {
                every { getWorkInfosForUniqueWorkFlow(WorkerScheduler.RESYNC_WORK_NAME) } returns flowOf(emptyList())
            }

        val viewModel = DataSourceSettingsViewModel(settingsRepo, scheduler, workManager)
        viewModel.sharingStarted = SharingStarted.Lazily
        return Triple(viewModel, deviceByDataType, scheduler)
    }

    @Test
    fun `changing a device stages the value without persisting`() =
        runTest {
            val (viewModel, deviceByDataType, scheduler) = buildViewModel()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()

            assertEquals("Watch A", viewModel.uiState.value.deviceByDataType[HealthDataType.HEART_RATE.name])
            assertTrue(viewModel.uiState.value.hasPendingChanges)
            assertTrue(deviceByDataType.value.isEmpty())
            coVerify(exactly = 0) { scheduler.scheduleResyncWorker() }

            job.cancel()
        }

    @Test
    fun `apply persists staged changes and triggers resync`() =
        runTest {
            val (viewModel, deviceByDataType, scheduler) = buildViewModel()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()
            viewModel.onApply()
            advanceUntilIdle()

            assertEquals("Watch A", deviceByDataType.value[HealthDataType.HEART_RATE.name])
            assertFalse(viewModel.uiState.value.hasPendingChanges)
            coVerify { scheduler.scheduleResyncWorker() }

            job.cancel()
        }

    @Test
    fun `device change notice shows when not dismissed and can be dismissed for this session`() =
        runTest {
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
        runTest {
            val (viewModel, _, _) = buildViewModel(deviceChangeNoticeDismissed = true)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.updateDevice(HealthDataType.HEART_RATE, "Watch A")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.showDeviceChangeNotice)

            job.cancel()
        }

    @Test
    fun `reverting a device back to its persisted value clears pending changes`() =
        runTest {
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
