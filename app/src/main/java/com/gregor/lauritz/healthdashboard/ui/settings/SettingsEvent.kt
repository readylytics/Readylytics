package com.gregor.lauritz.healthdashboard.ui.settings

import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.domain.backup.BackupFileInfo
import com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel

sealed interface SettingsEvent {
    data class GoalSleepHoursChanged(
        val hours: Float,
    ) : SettingsEvent

    data class HrvBaselineChanged(
        val text: String,
    ) : SettingsEvent

    data object HrvBaselineCleared : SettingsEvent

    data class RhrBaselineChanged(
        val text: String,
    ) : SettingsEvent

    data object RhrBaselineCleared : SettingsEvent

    data class SyncPreferenceChanged(
        val pref: SyncPreference,
    ) : SettingsEvent

    data class SyncIntervalChanged(
        val hours: Int,
    ) : SettingsEvent

    data class MaxHeartRateChanged(
        val text: String,
    ) : SettingsEvent

    data class AutoCalculateMaxHrChanged(
        val enabled: Boolean,
    ) : SettingsEvent

    data class ManualZoneEditingChanged(
        val enabled: Boolean,
    ) : SettingsEvent

    data class ZonePercentagesChanged(
        val z1Min: Float,
        val z1Max: Float,
        val z2Max: Float,
        val z3Max: Float,
        val z4Max: Float,
    ) : SettingsEvent

    data class ZoneBpmsChanged(
        val z1Min: Int,
        val z1Max: Int,
        val z2Max: Int,
        val z3Max: Int,
        val z4Max: Int,
    ) : SettingsEvent

    data class BirthdayChanged(
        val day: Int,
        val month: Int,
        val year: Int,
    ) : SettingsEvent

    data class GenderChanged(
        val gender: Gender?,
    ) : SettingsEvent

    data class HeightChanged(
        val heightCm: Float?,
    ) : SettingsEvent

    data class HrvOptimalThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class HrvWarningThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RhrOptimalThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RhrWarningThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RestingHrBeforeMinutesChanged(
        val minutes: Int,
    ) : SettingsEvent

    data class RestingHrAfterMinutesChanged(
        val minutes: Int,
    ) : SettingsEvent

    data class RestingHrPercentileChanged(
        val percentile: Int,
    ) : SettingsEvent

    data class ConsistencyThresholdChanged(
        val minutes: Int,
    ) : SettingsEvent

    data class ConsistencyEvaluationDaysChanged(
        val days: Int,
    ) : SettingsEvent

    data class ConsistencyBaselineDaysChanged(
        val days: Int,
    ) : SettingsEvent

    data class PaiScalingFactorChanged(
        val value: Float,
    ) : SettingsEvent

    data object ResetPaiScalingFactor : SettingsEvent

    data class StepGoalChanged(
        val steps: Int,
    ) : SettingsEvent

    data class AppThemeChanged(
        val theme: AppTheme,
    ) : SettingsEvent

    data class DynamicColorEnabledChanged(
        val enabled: Boolean,
    ) : SettingsEvent

    data object CreateLocalBackup : SettingsEvent

    data class RestoreLocalBackup(
        val file: BackupFileInfo,
    ) : SettingsEvent

    data object RestoreConfirmed : SettingsEvent

    data object RestoreDismissed : SettingsEvent

    data object DismissBackupError : SettingsEvent

    data class DeleteLocalBackup(
        val file: BackupFileInfo,
    ) : SettingsEvent

    data class ChangeBackupDirectory(
        val path: String,
    ) : SettingsEvent

    data class BackupScheduleChanged(
        val schedule: BackupSchedule,
    ) : SettingsEvent

    data class RetentionDaysEnabledChanged(
        val enabled: Boolean,
    ) : SettingsEvent

    data class RetentionDaysChanged(
        val days: Int,
    ) : SettingsEvent

    data object OpenSetPasswordDialog : SettingsEvent

    data object DismissSetPasswordDialog : SettingsEvent

    data class UpdateBackupPassword(
        val raw: String,
        val autoStartBackup: Boolean = false,
    ) : SettingsEvent

    data class VerifyBackupPassword(
        val test: String,
    ) : SettingsEvent

    data object ClearPasswordVerificationResult : SettingsEvent

    data object ResyncHealthConnect : SettingsEvent

    data class PhysiologyProfileChanged(
        val profile: PhysiologyProfile,
    ) : SettingsEvent

    data class CircadianThresholdOverrideChanged(
        val minutes: Int?,
    ) : SettingsEvent

    data object DismissThresholdError : SettingsEvent

    data object AboutDismissed : SettingsEvent

    data class TrimpModelChanged(
        val model: TrimpModel,
    ) : SettingsEvent

    data class BanisterMultiplierChanged(
        val value: Float,
    ) : SettingsEvent

    data class ChengBetaChanged(
        val value: Float,
    ) : SettingsEvent

    data class ItrimBChanged(
        val value: Float,
    ) : SettingsEvent

    data object ResetTrimpToProfileDefaults : SettingsEvent
}
