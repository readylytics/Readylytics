package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.FallbackThemeColor
import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.data.preferences.normalizeHypersomniaOnsetPercent
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

internal fun UserPreferences.withZonesAndDemographics(proto: UserPreferencesProto): UserPreferences =
    copy(
        zone1MinPercent = proto.zone1MinPercent,
        zone1MaxPercent = proto.zone1MaxPercent,
        zone2MaxPercent = proto.zone2MaxPercent,
        zone3MaxPercent = proto.zone3MaxPercent,
        zone4MaxPercent = proto.zone4MaxPercent,
        zone1MinBpm = proto.zone1MinBpm,
        zone1MaxBpm = proto.zone1MaxBpm,
        zone2MaxBpm = proto.zone2MaxBpm,
        zone3MaxBpm = proto.zone3MaxBpm,
        zone4MaxBpm = proto.zone4MaxBpm,
        age = proto.age,
        birthDate = migrateBirthdateFields(proto.birthDay, proto.birthMonth, proto.birthYear),
        gender = if (proto.hasGender()) Gender.fromString(proto.gender) else null,
        heightCm = if (proto.hasHeightCm()) proto.heightCm else null,
        isBirthdayConfigured = proto.isBirthdayConfigured,
        unitSystem =
            when (proto.unitSystem) {
                UnitSystemProto.UNIT_METRIC -> UnitSystem.METRIC
                UnitSystemProto.UNIT_IMPERIAL -> UnitSystem.IMPERIAL
                else -> SettingsDefaults.UNIT_SYSTEM
            },
        weekStartDay =
            when (proto.weekStartDay) {
                DayOfWeekProto.DAY_OF_WEEK_MONDAY -> DayOfWeek.MONDAY
                DayOfWeekProto.DAY_OF_WEEK_TUESDAY -> DayOfWeek.TUESDAY
                DayOfWeekProto.DAY_OF_WEEK_WEDNESDAY -> DayOfWeek.WEDNESDAY
                DayOfWeekProto.DAY_OF_WEEK_THURSDAY -> DayOfWeek.THURSDAY
                DayOfWeekProto.DAY_OF_WEEK_FRIDAY -> DayOfWeek.FRIDAY
                DayOfWeekProto.DAY_OF_WEEK_SATURDAY -> DayOfWeek.SATURDAY
                DayOfWeekProto.DAY_OF_WEEK_SUNDAY -> DayOfWeek.SUNDAY
                else -> SettingsDefaults.WEEK_START_DAY
            },
    )

internal fun UserPreferences.withThresholdsAndDisplay(proto: UserPreferencesProto): UserPreferences =
    copy(
        hrvOptimalThreshold = proto.hrvOptimalThreshold,
        hrvWarningThreshold = proto.hrvWarningThreshold,
        rhrOptimalThreshold = proto.rhrOptimalThreshold,
        rhrWarningThreshold = proto.rhrWarningThreshold,
        restingHrPercentile =
            if (proto.restingHrPercentile == 0) {
                SettingsDefaults.RESTING_HR_PERCENTILE
            } else {
                proto.restingHrPercentile.coerceIn(1, 15)
            },
        appTheme = AppTheme.valueOf(proto.appTheme.name.removePrefix("THEME_")),
        backupSchedule = BackupSchedule.valueOf(proto.backupSchedule.name.removePrefix("BACKUP_")),
        lastBackupTimestamp = proto.lastBackupTimestamp,
        consistencyThresholdMinutes = proto.consistencyThresholdMinutes,
        consistencyEvaluationDays = proto.consistencyEvaluationDays,
        consistencyBaselineDays = proto.consistencyBaselineDays,
        hrrToleranceSeconds =
            if (proto.hrrToleranceSeconds == 0) {
                SettingsDefaults.HRR_TOLERANCE_SECONDS
            } else {
                proto.hrrToleranceSeconds.coerceIn(
                    SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS,
                    SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS,
                )
            },
        bodyTempElevatedThresholdCelsius =
            if (proto.hasBodyTempElevatedThresholdCelsius()) {
                proto.bodyTempElevatedThresholdCelsius.coerceIn(
                    SettingsDefaults.MIN_BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS,
                    SettingsDefaults.MAX_BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS,
                )
            } else {
                SettingsDefaults.BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS
            },
        rasScalingFactor = proto.rasScalingFactor,
        stepGoal = proto.stepGoal,
        retentionDaysEnabled = proto.retentionDaysEnabled,
        retentionDays = proto.retentionDays,
    )

internal fun UserPreferences.withPaletteAndUi(proto: UserPreferencesProto): UserPreferences =
    copy(
        collapseHealthConnect = proto.collapseHealthConnect,
        collapseBaselinesThresholds = proto.collapseBaselinesThresholds,
        collapseDisplay = proto.collapseDisplay,
        collapseAdvanced = proto.collapseAdvanced,
        aboutDismissed = proto.aboutDismissed,
        installDate = proto.installDate,
        circadianThresholdOverride =
            if (proto.hasCircadianThresholdOverride()) proto.circadianThresholdOverride else null,
        dynamicColorEnabled = proto.dynamicColorEnabled,
        fallbackThemeColor =
            when (proto.fallbackThemeColor) {
                FallbackThemeColorProto.FALLBACK_GREEN_PERFORMANCE -> FallbackThemeColor.GREEN_PERFORMANCE
                FallbackThemeColorProto.FALLBACK_BLUE_TRUST -> FallbackThemeColor.BLUE_TRUST
                FallbackThemeColorProto.FALLBACK_PURPLE_INSIGHT -> FallbackThemeColor.PURPLE_INSIGHT
                FallbackThemeColorProto.FALLBACK_ICON_SIGNATURE -> FallbackThemeColor.ICON_SIGNATURE
                FallbackThemeColorProto.FALLBACK_ICON_ELEMENTS -> FallbackThemeColor.ICON_ELEMENTS
                else -> SettingsDefaults.FALLBACK_THEME_COLOR
            },
        isCustomPaletteEnabled = proto.isCustomPaletteEnabled,
        customSecondaryColor =
            if (proto.customSecondaryColor == 0L) {
                SettingsDefaults.CUSTOM_SECONDARY_COLOR
            } else {
                proto.customSecondaryColor
            },
        customTertiaryColor =
            if (proto.customTertiaryColor == 0L) {
                SettingsDefaults.CUSTOM_TERTIARY_COLOR
            } else {
                proto.customTertiaryColor
            },
        customPrimaryColor =
            if (proto.customPrimaryColor == 0L) {
                SettingsDefaults.CUSTOM_PRIMARY_COLOR
            } else {
                proto.customPrimaryColor
            },
    )

internal fun UserPreferences.withDevicesAndBackups(proto: UserPreferencesProto): UserPreferences =
    copy(
        primaryDeviceName = if (proto.hasPrimaryDeviceName()) proto.primaryDeviceName else null,
        deviceByDataType = proto.deviceByDataTypeMap.toMap(),
        backupDirectoryUri = if (proto.hasBackupDirectoryUri()) proto.backupDirectoryUri else null,
        backupPasswordHash = if (proto.hasBackupPasswordHash()) proto.backupPasswordHash else null,
        scoringZoneId = proto.scoringZoneId,
        deviceChangeNoticeDismissed = proto.deviceChangeNoticeDismissed,
        bulkDisplayModeNoticeDismissed = proto.bulkDisplayModeNoticeDismissed,
        lastGlobalDisplayMode =
            when (proto.lastGlobalDisplayMode) {
                DashboardCardDisplayModeProto.DASHBOARD_CARD_DISPLAY_MODE_VALUE -> DashboardCardDisplayMode.VALUE
                DashboardCardDisplayModeProto.DASHBOARD_CARD_DISPLAY_MODE_GAUGE -> DashboardCardDisplayMode.GAUGE
                DashboardCardDisplayModeProto.DASHBOARD_CARD_DISPLAY_MODE_BAR -> DashboardCardDisplayMode.BAR
                else -> null
            },
    )

internal fun UserPreferences.withScoringProfiles(
    proto: UserPreferencesProto,
    profile: PhysiologyProfile,
): UserPreferences =
    copy(
        trimpModel =
            when (proto.trimpMethod) {
                TrimpMethodProto.TRIMP_ITRIMP -> TrimpModel.I_TRIMP
                TrimpMethodProto.TRIMP_CHENG -> TrimpModel.CHENG
                else -> TrimpModel.BANISTER
            },
        banisterMultiplier = if (proto.rasCalibration > 0f) proto.rasCalibration else profile.banisterMultiplier,
        chengBeta = if (proto.chengBeta > 0f) proto.chengBeta else profile.defaultChengBeta,
        itrimB = if (proto.itrimpB > 0f) proto.itrimpB else profile.defaultItrimB,
        strainLoadSourceMode =
            when (proto.strainLoadSourceMode) {
                LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY -> LoadSourceMode.WORKOUT_ONLY
                LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE -> LoadSourceMode.EVERYDAY_HEART_RATE
                else -> SettingsDefaults.STRAIN_LOAD_SOURCE_MODE
            },
        rasSourceMode =
            when (proto.rasSourceMode) {
                LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY -> LoadSourceMode.WORKOUT_ONLY
                LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE -> LoadSourceMode.EVERYDAY_HEART_RATE
                else -> SettingsDefaults.RAS_SOURCE_MODE
            },
        sleepScoreWeightProfile = proto.sleepScoreWeightProfile.toDomainProfile(),
        hypersomniaOnsetPercent =
            if (proto.hasHypersomniaOnsetPercent()) {
                normalizeHypersomniaOnsetPercent(proto.hypersomniaOnsetPercent)
            } else {
                SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT
            },
    )

internal fun UserPreferences.withResidualFatigue(proto: UserPreferencesProto): UserPreferences =
    copy(
        residualFatigueEnabled =
            if (proto.hasResidualFatigueEnabled()) {
                proto.residualFatigueEnabled
            } else {
                SettingsDefaults.RESIDUAL_FATIGUE_ENABLED
            },
        residualFatigueHalfLifeHours =
            if (proto.hasResidualFatigueHalfLifeHours() && proto.residualFatigueHalfLifeHours > 0f) {
                proto.residualFatigueHalfLifeHours.coerceIn(
                    SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                    SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                )
            } else {
                SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS
            },
        residualFatigueGain =
            if (proto.hasResidualFatigueGain() && proto.residualFatigueGain > 0f) {
                proto.residualFatigueGain.coerceIn(
                    SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN,
                    SettingsDefaults.MAX_RESIDUAL_FATIGUE_GAIN,
                )
            } else {
                SettingsDefaults.RESIDUAL_FATIGUE_GAIN
            },
    )

internal fun UserPreferences.withRecalcAndVersion(proto: UserPreferencesProto): UserPreferences =
    copy(
        lastRecalcSleepScoreWeightProfile =
            if (proto.hasLastRecalcSleepScoreWeightProfile()) {
                proto.lastRecalcSleepScoreWeightProfile.toDomainProfile()
            } else {
                null
            },
        lastRecalcGoalSleepHours =
            if (proto.hasLastRecalcGoalSleepHours()) proto.lastRecalcGoalSleepHours else null,
        lastRecalcHypersomniaOnsetPercent =
            if (proto.hasLastRecalcHypersomniaOnsetPercent()) proto.lastRecalcHypersomniaOnsetPercent else null,
        scoringVersion = proto.scoringVersion,
        trimpNormalizationMigrated = proto.trimpNormalizationMigrated,
    )

private fun migrateBirthdateFields(
    day: Int,
    month: Int,
    year: Int,
): String? {
    if (day == 0 || month == 0 || year == 0) return null
    return try {
        val clampedMonth = month.coerceIn(1, 12)
        val daysInMonth = YearMonth.of(year, clampedMonth).lengthOfMonth()
        val clampedDay = day.coerceIn(1, daysInMonth)
        val birthDate = LocalDate.of(year, clampedMonth, clampedDay)
        if (birthDate > LocalDate.now()) null else birthDate.toString()
    } catch (_: Exception) {
        null
    }
}
