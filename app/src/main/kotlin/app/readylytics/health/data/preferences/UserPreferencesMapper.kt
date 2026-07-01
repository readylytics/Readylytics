package app.readylytics.health.data.preferences

import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.TrimpModel
import java.time.LocalDate
import java.time.YearMonth

fun PhysiologyProfileProto.toDomainProfile(): PhysiologyProfile =
    when (this) {
        PhysiologyProfileProto.PROFILE_ATHLETE -> PhysiologyProfile.ATHLETE
        PhysiologyProfileProto.PROFILE_SEDENTARY -> PhysiologyProfile.SEDENTARY
        else -> PhysiologyProfile.ACTIVE
    }

fun UserPreferencesProto.toDomainModel(): UserPreferences {
    val profile = physiologyProfile.toDomainProfile()
    return UserPreferences(
        goalSleepHours = goalSleepHours,
        hrvBaselineOverride = if (hasHrvBaselineOverride()) hrvBaselineOverride else null,
        rhrBaselineOverride = if (hasRhrBaselineOverride()) rhrBaselineOverride else null,
        syncPreference = SyncPreference.valueOf(syncPreference.name.removePrefix("SYNC_")),
        syncIntervalHours = syncIntervalHours,
        lastSyncTimestamp = lastSyncTimestamp,
        maxHeartRate = maxHeartRate,
        autoCalculateMaxHr = autoCalculateMaxHr,
        manualZoneEditing = manualZoneEditing,
        zone1MinPercent = zone1MinPercent,
        zone1MaxPercent = zone1MaxPercent,
        zone2MaxPercent = zone2MaxPercent,
        zone3MaxPercent = zone3MaxPercent,
        zone4MaxPercent = zone4MaxPercent,
        zone1MinBpm = zone1MinBpm,
        zone1MaxBpm = zone1MaxBpm,
        zone2MaxBpm = zone2MaxBpm,
        zone3MaxBpm = zone3MaxBpm,
        zone4MaxBpm = zone4MaxBpm,
        age = age,
        birthDate = migrateBirthdateFields(birthDay, birthMonth, birthYear),
        gender = if (hasGender()) Gender.fromString(gender) else null,
        heightCm = if (hasHeightCm()) heightCm else null,
        hrvOptimalThreshold = hrvOptimalThreshold,
        hrvWarningThreshold = hrvWarningThreshold,
        rhrOptimalThreshold = rhrOptimalThreshold,
        rhrWarningThreshold = rhrWarningThreshold,
        restingHrPercentile =
            if (restingHrPercentile ==
                0
            ) {
                SettingsDefaults.RESTING_HR_PERCENTILE
            } else {
                restingHrPercentile.coerceIn(1, 15)
            },
        appTheme = AppTheme.valueOf(appTheme.name.removePrefix("THEME_")),
        backupSchedule = BackupSchedule.valueOf(backupSchedule.name.removePrefix("BACKUP_")),
        lastBackupTimestamp = lastBackupTimestamp,
        consistencyThresholdMinutes = consistencyThresholdMinutes,
        consistencyEvaluationDays = consistencyEvaluationDays,
        consistencyBaselineDays = consistencyBaselineDays,
        hrrToleranceSeconds =
            if (hrrToleranceSeconds == 0) {
                SettingsDefaults.HRR_TOLERANCE_SECONDS
            } else {
                hrrToleranceSeconds.coerceIn(15, 60)
            },
        rasScalingFactor = rasScalingFactor,
        stepGoal = stepGoal,
        retentionDaysEnabled = retentionDaysEnabled,
        retentionDays = retentionDays,
        collapseHealthConnect = collapseHealthConnect,
        collapseBaselinesThresholds = collapseBaselinesThresholds,
        collapseDisplay = collapseDisplay,
        collapseAdvanced = collapseAdvanced,
        aboutDismissed = aboutDismissed,
        physiologyProfile = profile,
        installDate = installDate,
        circadianThresholdOverride = if (hasCircadianThresholdOverride()) circadianThresholdOverride else null,
        dynamicColorEnabled = dynamicColorEnabled,
        fallbackThemeColor =
            when (fallbackThemeColor) {
                FallbackThemeColorProto.FALLBACK_GREEN_PERFORMANCE -> FallbackThemeColor.GREEN_PERFORMANCE
                FallbackThemeColorProto.FALLBACK_BLUE_TRUST -> FallbackThemeColor.BLUE_TRUST
                FallbackThemeColorProto.FALLBACK_PURPLE_INSIGHT -> FallbackThemeColor.PURPLE_INSIGHT
                FallbackThemeColorProto.FALLBACK_ICON_SIGNATURE -> FallbackThemeColor.ICON_SIGNATURE
                FallbackThemeColorProto.FALLBACK_ICON_ELEMENTS -> FallbackThemeColor.ICON_ELEMENTS
                else -> SettingsDefaults.FALLBACK_THEME_COLOR
            },
        trimpModel =
            when (trimpMethod) {
                TrimpMethodProto.TRIMP_ITRIMP -> TrimpModel.I_TRIMP
                TrimpMethodProto.TRIMP_CHENG -> TrimpModel.CHENG
                else -> TrimpModel.BANISTER
            },
        banisterMultiplier = if (rasCalibration > 0f) rasCalibration else profile.banisterMultiplier,
        chengBeta = if (this.chengBeta > 0f) this.chengBeta else profile.defaultChengBeta,
        itrimB = if (itrimpB > 0f) itrimpB else profile.defaultItrimB,
        primaryDeviceName = if (hasPrimaryDeviceName()) primaryDeviceName else null,
        deviceByDataType = deviceByDataTypeMap.toMap(),
        backupDirectoryUri = if (hasBackupDirectoryUri()) backupDirectoryUri else null,
        backupPasswordHash = if (hasBackupPasswordHash()) backupPasswordHash else null,
        isBirthdayConfigured = isBirthdayConfigured,
        unitSystem =
            when (unitSystem) {
                UnitSystemProto.UNIT_METRIC -> UnitSystem.METRIC
                UnitSystemProto.UNIT_IMPERIAL -> UnitSystem.IMPERIAL
                else -> SettingsDefaults.UNIT_SYSTEM
            },
        backgroundSyncEnabled = backgroundSyncEnabled,
        backgroundSyncIntervalMinutes =
            if (backgroundSyncIntervalMinutes ==
                0
            ) {
                SettingsDefaults.BACKGROUND_SYNC_INTERVAL.minutes
            } else {
                backgroundSyncIntervalMinutes
            },
        isCustomPaletteEnabled = isCustomPaletteEnabled,
        customSecondaryColor =
            if (customSecondaryColor ==
                0L
            ) {
                SettingsDefaults.CUSTOM_SECONDARY_COLOR
            } else {
                customSecondaryColor
            },
        customTertiaryColor =
            if (customTertiaryColor ==
                0L
            ) {
                SettingsDefaults.CUSTOM_TERTIARY_COLOR
            } else {
                customTertiaryColor
            },
        customPrimaryColor =
            if (customPrimaryColor ==
                0L
            ) {
                SettingsDefaults.CUSTOM_PRIMARY_COLOR
            } else {
                customPrimaryColor
            },
        scoringZoneId = scoringZoneId,
        deviceChangeNoticeDismissed = deviceChangeNoticeDismissed,
        strainLoadSourceMode =
            when (strainLoadSourceMode) {
                LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY -> LoadSourceMode.WORKOUT_ONLY
                LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE -> LoadSourceMode.EVERYDAY_HEART_RATE
                else -> SettingsDefaults.STRAIN_LOAD_SOURCE_MODE
            },
        rasSourceMode =
            when (rasSourceMode) {
                LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY -> LoadSourceMode.WORKOUT_ONLY
                LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE -> LoadSourceMode.EVERYDAY_HEART_RATE
                else -> SettingsDefaults.RAS_SOURCE_MODE
            },
    )
}

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
    } catch (e: Exception) {
        null
    }
}
