package app.readylytics.health.feature.onboarding

import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.DeviceSettings
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.PhysiologySettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.scoring.TrimpModel
import app.readylytics.health.domain.service.BmiService
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.user.UserProfileActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

class FirstSetupFlowHarness(
    private val advanceUntilIdle: () -> Unit,
) {
    val preferences = MutableStateFlow(UserPreferences())

    val physiologySettings: PhysiologySettings = PhysiologyPort()
    val displaySettings: DisplaySettings = DisplayPort()
    val deviceSettings: DeviceSettings = DevicePort()
    val reader: UserPreferencesReader = Reader()
    val userProfileActions = UserProfileActionsPort()
    val healthDataRefresh = HealthDataRefreshPort()

    fun buildOnboardingViewModel(): OnboardingViewModel =
        OnboardingViewModel(
            physiologySettings = physiologySettings,
            displaySettings = displaySettings,
            deviceSettings = deviceSettings,
            bmiService = BmiService(),
        )

    fun seedProfile(
        birthDate: LocalDate,
        heightCm: Float,
        physiologyProfile: PhysiologyProfile,
        unitSystem: UnitSystem,
    ) {
        preferences.value =
            preferences.value.copy(
                birthDate = birthDate.toString(),
                isBirthdayConfigured = true,
                heightCm = heightCm,
                physiologyProfile = physiologyProfile,
                unitSystem = unitSystem,
            )
    }

    fun advanceUntilIdle() {
        advanceUntilIdle.invoke()
    }

    private inner class Reader : UserPreferencesReader {
        override val userPreferences: StateFlow<UserPreferences> = preferences
    }

    private inner class PhysiologyPort : PhysiologySettings {
        override suspend fun updateBirthday(date: LocalDate) {
            preferences.value =
                preferences.value.copy(
                    birthDate = date.toString(),
                    isBirthdayConfigured = true,
                )
        }

        override suspend fun updateGender(gender: String?) {
            preferences.value = preferences.value.copy(gender = Gender.fromStringOrNull(gender))
        }

        override suspend fun updateHeight(heightCm: Float?) {
            preferences.value = preferences.value.copy(heightCm = heightCm)
        }

        override suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) {
            preferences.value = preferences.value.copy(physiologyProfile = profile)
        }
    }

    private inner class DisplayPort : DisplaySettings {
        override suspend fun updateDynamicColorEnabled(enabled: Boolean) {
            preferences.value = preferences.value.copy(dynamicColorEnabled = enabled)
        }

        override suspend fun updateUnitSystem(unitSystem: UnitSystem) {
            preferences.value = preferences.value.copy(unitSystem = unitSystem)
        }

        override suspend fun updateAppTheme(theme: app.readylytics.health.data.preferences.AppTheme) =
            error("Unexpected call: updateAppTheme")

        override suspend fun updateFallbackThemeColor(
            color: app.readylytics.health.data.preferences.FallbackThemeColor,
        ) = error("Unexpected call: updateFallbackThemeColor")

        override suspend fun updateCustomPaletteEnabled(enabled: Boolean) =
            error("Unexpected call: updateCustomPaletteEnabled")

        override suspend fun updateCustomPrimaryColor(color: Long) = error("Unexpected call: updateCustomPrimaryColor")

        override suspend fun updateCustomSecondaryColor(color: Long) =
            error("Unexpected call: updateCustomSecondaryColor")

        override suspend fun updateCustomTertiaryColor(color: Long) =
            error("Unexpected call: updateCustomTertiaryColor")

        override suspend fun updateRasScalingFactor(value: Float) = error("Unexpected call: updateRasScalingFactor")

        override suspend fun updateStepGoal(steps: Int) = error("Unexpected call: updateStepGoal")

        override suspend fun updateRetentionDaysEnabled(enabled: Boolean) =
            error("Unexpected call: updateRetentionDaysEnabled")

        override suspend fun updateRetentionDays(days: Int) = error("Unexpected call: updateRetentionDays")

        override suspend fun updateTrimpModel(model: TrimpModel) = error("Unexpected call: updateTrimpModel")

        override suspend fun updateBanisterMultiplier(value: Float) = error("Unexpected call: updateBanisterMultiplier")

        override suspend fun updateChengBeta(value: Float) = error("Unexpected call: updateChengBeta")

        override suspend fun updateItrimB(value: Float) = error("Unexpected call: updateItrimB")
    }

    private inner class DevicePort : DeviceSettings {
        override suspend fun getAvailableDevices(): List<String> = error("Unexpected call: getAvailableDevices")

        override suspend fun clearDeviceCache() = error("Unexpected call: clearDeviceCache")

        override suspend fun migrateDeviceSelectionIfNeeded() = error("Unexpected call: migrateDeviceSelectionIfNeeded")

        override suspend fun updatePrimaryDevice(deviceName: String?) = error("Unexpected call: updatePrimaryDevice")

        override suspend fun updateDeviceForDataType(
            dataTypeKey: String,
            deviceLabel: String?,
        ) = error("Unexpected call: updateDeviceForDataType")

        override suspend fun applyDeviceOverrides(overrides: Map<String, String?>) =
            error("Unexpected call: applyDeviceOverrides")

        override suspend fun updateDeviceChangeNoticeDismissed(dismissed: Boolean) =
            error("Unexpected call: updateDeviceChangeNoticeDismissed")
    }

    inner class UserProfileActionsPort : UserProfileActions {
        override suspend fun updateBirthday(date: LocalDate): Result<Unit> {
            preferences.value =
                preferences.value.copy(
                    birthDate = date.toString(),
                    isBirthdayConfigured = true,
                )
            return Result.success(Unit)
        }

        override suspend fun calculateAndSetMaxHr(): Result<Unit> = error("Unexpected call: calculateAndSetMaxHr")
    }

    inner class HealthDataRefreshPort : HealthDataRefresh {
        var refreshCalls = 0
            private set

        override suspend fun refreshAffectedWindow() {
            refreshCalls += 1
        }
    }
}
