package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted

/** Shared fake-repository harness for [DashboardCardsSettingsViewModel] tests. */
internal data class DashboardCardsSettingsHarness(
    val viewModel: DashboardCardsSettingsViewModel,
    val dashboardConfigs: MutableStateFlow<List<CardConfiguration>>,
    val vitalsConfigs: MutableStateFlow<List<CardConfiguration>>,
    val displaySettings: DisplaySettings,
    val sleepTopCards: MutableStateFlow<List<SleepTopCardConfiguration>>,
    val sleepMetricCards: MutableStateFlow<List<SleepMetricCardConfiguration>>,
    val workoutConfigs: MutableStateFlow<List<CardConfiguration>>,
)

internal fun buildDashboardCardsSettingsHarness(
    noticeDismissed: Boolean = false,
    currentGlobalMode: DashboardCardDisplayMode? = null,
    initialConfigs: List<CardConfiguration> =
        listOf(
            CardConfiguration(cardId = CardId.SLEEP_SCORE, requestedDisplayMode = null),
            CardConfiguration(cardId = CardId.HEART_RATE, requestedDisplayMode = null),
        ),
    initialVitalsConfigs: List<CardConfiguration> =
        listOf(CardConfiguration(cardId = CardId.RESTING_HR, requestedDisplayMode = null)),
    initialSleepTopCards: List<SleepTopCardConfiguration> = emptyList(),
    initialSleepMetricCards: List<SleepMetricCardConfiguration> = emptyList(),
    initialWorkoutConfigs: List<CardConfiguration> = emptyList(),
): DashboardCardsSettingsHarness {
    val prefsFlow =
        MutableStateFlow(
            UserPreferences(
                bulkDisplayModeNoticeDismissed = noticeDismissed,
                lastGlobalDisplayMode = currentGlobalMode,
            ),
        )
    val settingsReader =
        mockk<UserPreferencesReader> {
            every { userPreferences } returns prefsFlow
        }
    val configsFlow = MutableStateFlow(initialConfigs)
    val vitalsConfigsFlow = MutableStateFlow(initialVitalsConfigs)
    val sleepTopCardsFlow = MutableStateFlow(initialSleepTopCards)
    val sleepMetricCardsFlow = MutableStateFlow(initialSleepMetricCards)
    val workoutConfigsFlow = MutableStateFlow(initialWorkoutConfigs)
    val displaySettings = mockk<DisplaySettings>(relaxed = true)

    val viewModel =
        DashboardCardsSettingsViewModel(
            settingsReader,
            displaySettings,
            fakeDashboardRepository(configsFlow),
            fakeVitalsRepository(vitalsConfigsFlow),
            fakeSleepRepository(sleepTopCardsFlow, sleepMetricCardsFlow),
            fakeWorkoutsRepository(workoutConfigsFlow),
        )
    viewModel.sharingStarted = SharingStarted.Lazily
    return DashboardCardsSettingsHarness(
        viewModel,
        configsFlow,
        vitalsConfigsFlow,
        displaySettings,
        sleepTopCardsFlow,
        sleepMetricCardsFlow,
        workoutConfigsFlow,
    )
}

private fun fakeDashboardRepository(flow: MutableStateFlow<List<CardConfiguration>>) =
    mockk<CardConfigurationRepository> {
        every { dashboardCardConfigurations() } returns flow
        coEvery { updateDashboardCardConfigurations(any()) } coAnswers { flow.value = firstArg() }
    }

private fun fakeVitalsRepository(flow: MutableStateFlow<List<CardConfiguration>>) =
    mockk<VitalsLayoutRepository> {
        every { vitalsCardConfigurations() } returns flow
        coEvery { updateVitalsCardConfigurations(any()) } coAnswers { flow.value = firstArg() }
    }

private fun fakeSleepRepository(
    topCards: MutableStateFlow<List<SleepTopCardConfiguration>>,
    metricCards: MutableStateFlow<List<SleepMetricCardConfiguration>>,
) = mockk<SleepLayoutRepository> {
    every { sleepTopCardConfigurations() } returns topCards
    every { sleepMetricCardConfigurations() } returns metricCards
    coEvery { updateSleepTopCardConfigurations(any()) } coAnswers { topCards.value = firstArg() }
    coEvery { updateSleepMetricCardConfigurations(any()) } coAnswers { metricCards.value = firstArg() }
}

private fun fakeWorkoutsRepository(flow: MutableStateFlow<List<CardConfiguration>>) =
    mockk<WorkoutsLayoutRepository> {
        every { workoutCardConfigurations() } returns flow
        coEvery { updateWorkoutCardConfigurations(any()) } coAnswers { flow.value = firstArg() }
    }
