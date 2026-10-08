package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.FallbackThemeColor
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.preferences.DisplayGoalSettings
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.FatigueSettings
import app.readylytics.health.core.model.domain.preferences.ThemeSettings
import app.readylytics.health.core.model.domain.preferences.TrimpSettings
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import java.time.DayOfWeek

internal class ThemeSettingsDelegate(
    private val ui: UIPreferences,
) : ThemeSettings {
    override suspend fun updateAppTheme(theme: AppTheme) = ui.updateAppTheme(theme)

    override suspend fun updateDynamicColorEnabled(enabled: Boolean) = ui.updateDynamicColorEnabled(enabled)

    override suspend fun updateFallbackThemeColor(color: FallbackThemeColor) = ui.updateFallbackThemeColor(color)

    override suspend fun updateCustomPaletteEnabled(enabled: Boolean) = ui.updateCustomPaletteEnabled(enabled)

    override suspend fun updateCustomPrimaryColor(color: Long) = ui.updateCustomPrimaryColor(color)

    override suspend fun updateCustomSecondaryColor(color: Long) = ui.updateCustomSecondaryColor(color)

    override suspend fun updateCustomTertiaryColor(color: Long) = ui.updateCustomTertiaryColor(color)
}

internal class DisplayGoalSettingsDelegate(
    private val ui: UIPreferences,
    private val sleep: SleepPreferences,
) : DisplayGoalSettings {
    override suspend fun updateUnitSystem(unitSystem: UnitSystem) = ui.updateUnitSystem(unitSystem)

    override suspend fun updateWeekStartDay(day: DayOfWeek) = ui.updateWeekStartDay(day)

    override suspend fun updateHrrToleranceSeconds(value: Int) = sleep.updateHrrToleranceSeconds(value)

    override suspend fun updateRasScalingFactor(value: Float) = sleep.updateRasScalingFactor(value)

    override suspend fun updateStepGoal(steps: Int) = sleep.updateStepGoal(steps)

    override suspend fun updateRetentionDaysEnabled(enabled: Boolean) = sleep.updateRetentionDaysEnabled(enabled)

    override suspend fun updateRetentionDays(days: Int) = sleep.updateRetentionDays(days)
}

internal class TrimpSettingsDelegate(
    private val physiology: PhysiologyPreferences,
) : TrimpSettings {
    override suspend fun updateTrimpModel(model: TrimpModel) = physiology.updateTrimpModel(model)

    override suspend fun updateBanisterMultiplier(value: Float) = physiology.updateBanisterMultiplier(value)

    override suspend fun updateChengBeta(value: Float) = physiology.updateChengBeta(value)

    override suspend fun updateItrimB(value: Float) = physiology.updateItrimB(value)
}

internal class FatigueSettingsDelegate(
    private val physiology: PhysiologyPreferences,
    private val ui: UIPreferences,
) : FatigueSettings {
    override suspend fun updateResidualFatigueHalfLifeHours(hours: Float) =
        physiology.updateResidualFatigueHalfLifeHours(hours)

    override suspend fun updateResidualFatigueGain(value: Float) = physiology.updateResidualFatigueGain(value)

    override suspend fun resetResidualFatigueToDefaults() = physiology.resetResidualFatigueToDefaults()

    override suspend fun updateTrainingReadinessParameters(
        scale: Float,
        weight: Float,
    ) = physiology.updateTrainingReadinessParameters(scale, weight)

    override suspend fun resetTrainingReadinessToDefaults() = physiology.resetTrainingReadinessToDefaults()

    override suspend fun updateAppliedTrainingReadinessParameters(config: TrainingReadinessConfig) =
        physiology.updateAppliedTrainingReadinessParameters(config)

    override suspend fun updateBulkDisplayModeNoticeDismissed(dismissed: Boolean) =
        ui.updateBulkDisplayModeNoticeDismissed(dismissed)

    override suspend fun updateLastGlobalDisplayMode(mode: DashboardCardDisplayMode?) =
        ui.updateLastGlobalDisplayMode(mode)
}

internal class DisplaySettingsDelegate(
    theme: ThemeSettings,
    displayGoal: DisplayGoalSettings,
    trimp: TrimpSettings,
    fatigue: FatigueSettings,
) : DisplaySettings,
    ThemeSettings by theme,
    DisplayGoalSettings by displayGoal,
    TrimpSettings by trimp,
    FatigueSettings by fatigue {
    constructor(
        ui: UIPreferences,
        sleep: SleepPreferences,
        physiology: PhysiologyPreferences,
    ) : this(
        theme = ThemeSettingsDelegate(ui),
        displayGoal = DisplayGoalSettingsDelegate(ui, sleep),
        trimp = TrimpSettingsDelegate(physiology),
        fatigue = FatigueSettingsDelegate(physiology, ui),
    )
}
