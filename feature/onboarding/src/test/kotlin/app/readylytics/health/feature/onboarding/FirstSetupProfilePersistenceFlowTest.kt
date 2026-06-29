package app.readylytics.health.feature.onboarding

import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class FirstSetupProfilePersistenceFlowTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun saveProfile_persistsBirthdayHeightProfileUnitSystemDynamicColor_andBirthdayConfigured() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(mainDispatcher)
            val harness = FirstSetupFlowHarness(
                advanceUntilIdle = testScheduler::advanceUntilIdle,
            )
            val viewModel = harness.buildOnboardingViewModel()

            viewModel.saveProfile(
                birthDate = LocalDate.of(1990, 6, 15),
                gender = "Female",
                physiologyProfile = PhysiologyProfile.ACTIVE,
                dynamicColorEnabled = true,
                unitSystem = UnitSystem.METRIC,
                heightCm = 172.5f,
                onComplete = {},
            )

            harness.advanceUntilIdle()

            val prefs = harness.preferences.value
            assertEquals("1990-06-15", prefs.birthDate)
            assertEquals(app.readylytics.health.data.preferences.Gender.FEMALE, prefs.gender)
            assertEquals(PhysiologyProfile.ACTIVE, prefs.physiologyProfile)
            assertEquals(true, prefs.dynamicColorEnabled)
            assertEquals(UnitSystem.METRIC, prefs.unitSystem)
            assertEquals(172.5f, prefs.heightCm)
            assertEquals(true, prefs.isBirthdayConfigured)
        }
}
