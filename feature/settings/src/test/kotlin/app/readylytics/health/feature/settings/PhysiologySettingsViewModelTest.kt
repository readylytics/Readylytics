package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.PhysiologySettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.domain.sync.HealthDataRefresh
import app.readylytics.health.core.model.domain.user.UserProfileActions
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PhysiologySettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var physiologySettings: PhysiologySettings
    private lateinit var displaySettings: DisplaySettings
    private lateinit var userUseCase: UserProfileActions
    private lateinit var healthDataRefresh: HealthDataRefresh
    private lateinit var viewModel: PhysiologySettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepo =
            mockk {
                every { userPreferences } returns flowOf(UserPreferences(scoringZoneId = "Pacific/Kiritimati"))
            }
        physiologySettings = mockk(relaxed = true)
        displaySettings = mockk(relaxed = true)
        userUseCase = mockk(relaxed = true)
        healthDataRefresh = mockk(relaxed = true)
        viewModel = buildViewModel(settingsRepo = settingsRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(settingsRepo: UserPreferencesReader = this.settingsRepo): PhysiologySettingsViewModel =
        PhysiologySettingsViewModel(
            settingsRepo = settingsRepo,
            physiologySettings = physiologySettings,
            displaySettings = displaySettings,
            userUseCase = userUseCase,
            healthDataRefresh = healthDataRefresh,
            clock = Clock.fixed(Instant.parse("2026-01-01T10:30:00Z"), ZoneId.of("Pacific/Honolulu")),
        )

    @Test
    fun `unparseable stored birth date is gracefully mapped to null`() =
        runTest(testDispatcher) {
            val userPrefsFlow =
                MutableStateFlow(
                    UserPreferences(birthDate = "invalid-date-format"),
                )
            val settingsRepo =
                mockk<UserPreferencesReader>(relaxed = true) {
                    every { userPreferences } returns userPrefsFlow
                }
            val viewModel = buildViewModel(settingsRepo = settingsRepo)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.birthDate)
            job.cancel()
        }

    @Test
    fun `uiState birthdayBounds follows scoring zone`() =
        runTest(testDispatcher) {
            // Clock is 2026-01-01 00:30 in Honolulu but already 2026-01-02 00:30 in Kiritimati (scoring zone).
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }
            advanceUntilIdle()

            assertEquals(LocalDate.of(2026, 1, 2), viewModel.uiState.value.birthdayBounds.maxDate)
            assertEquals(1900..2026, viewModel.uiState.value.birthdayBounds.yearRange)
            job.cancel()
        }

    @Test
    fun validateBirthdayDay_15_succeeds() {
        val result = viewModel.validateBirthdayDayForUpdate("15")
        assert(result.isSuccess)
    }

    @Test
    fun validateBirthdayDay_32_fails() {
        val result = viewModel.validateBirthdayDayForUpdate("32")
        assert(result.isFailure)
    }

    @Test
    fun validateHeight_175_succeeds() {
        val result = viewModel.validateHeightForUpdate("175.5")
        assert(result.isSuccess)
    }

    @Test
    fun validateHeight_tooShort_fails() {
        val result = viewModel.validateHeightForUpdate("50")
        assert(result.isFailure)
    }

    @Test
    fun onEvent_birthdayValid_delegatesWithoutSchedulingAnotherRecompute() {
        val birthDate = LocalDate.of(1990, 6, 15)

        viewModel.onEvent(SettingsEvent.BirthdayChanged(date = birthDate))

        coVerify(timeout = 1000, exactly = 1) { userUseCase.updateBirthday(birthDate) }
        coVerify(timeout = 1000, exactly = 0) { healthDataRefresh.refreshHistorical() }
    }

    @Test
    fun `birthday today in scoring zone updates the profile`() {
        val today = LocalDate.of(2026, 1, 2)
        viewModel.onEvent(SettingsEvent.BirthdayChanged(date = today))
        coVerify(timeout = 1000, exactly = 1) { userUseCase.updateBirthday(today) }
    }

    @Test
    fun onEvent_birthdayInFuture_doesNotUpdateUserProfileActions() {
        val futureDate = LocalDate.of(2026, 1, 3)
        viewModel.onEvent(SettingsEvent.BirthdayChanged(date = futureDate))
        coVerify(timeout = 100, inverse = true) { userUseCase.updateBirthday(any()) }
    }

    @Test
    fun onEvent_physiologyProfileChanged_doesNotUpdateResidualFatigueSettings() {
        viewModel.onEvent(
            SettingsEvent.PhysiologyProfileChanged(
                app.readylytics.health.core.model.data.preferences.PhysiologyProfile.ATHLETE,
            ),
        )

        coVerify(timeout = 1000, exactly = 1) {
            physiologySettings.updatePhysiologyProfile(
                app.readylytics.health.core.model.data.preferences.PhysiologyProfile.ATHLETE,
            )
        }
        coVerify(timeout = 1000, exactly = 0) { displaySettings.updateResidualFatigueHalfLifeHours(any()) }
        coVerify(timeout = 1000, exactly = 0) { displaySettings.updateResidualFatigueGain(any()) }
        coVerify(timeout = 1000, exactly = 0) { displaySettings.resetResidualFatigueToDefaults() }
    }

    @Test
    fun onEvent_vo2MaxMethodChanged_updatesPrefAndRefreshesHistoricalExactlyOnce() {
        viewModel.onEvent(
            SettingsEvent.Vo2MaxEstimationMethodChanged(Vo2MaxEstimationMethod.MATERKO_ADAPTED),
        )

        coVerify(timeout = 1000, exactly = 1) {
            physiologySettings.updateVo2MaxEstimationMethod(Vo2MaxEstimationMethod.MATERKO_ADAPTED)
        }
        coVerify(timeout = 1000, exactly = 1) { healthDataRefresh.refreshHistorical() }
    }

    @Test
    fun onEvent_vo2MaxSourceModeChanged_updatesPrefAndRefreshesHistoricalExactlyOnce() {
        viewModel.onEvent(
            SettingsEvent.Vo2MaxSourceModeChanged(Vo2MaxSourceMode.ESTIMATED_ONLY),
        )

        coVerify(timeout = 1000, exactly = 1) {
            physiologySettings.updateVo2MaxSourceMode(Vo2MaxSourceMode.ESTIMATED_ONLY)
        }
        coVerify(timeout = 1000, exactly = 1) { healthDataRefresh.refreshHistorical() }
    }
}
