package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun UserPreferencesProto.Builder.applySyncAndBaselineFields(
    domain: UserPreferences,
): UserPreferencesProto.Builder =
    apply {
        setGoalSleepHours(domain.goalSleepHours)
        setCoreMergeGapMinutes(domain.coreMergeGapMinutes)
        setSupplementalCutoffMinutesOfDay(domain.supplementalCutoffMinutesOfDay)
        setMinimumCountedSleepSegmentMinutes(domain.minimumCountedSleepSegmentMinutes)
        setSupplementalArchitectureCoveragePercent(domain.supplementalArchitectureCoveragePercent)
        setSyncPreference(SyncPreferenceProto.valueOf("SYNC_${domain.syncPreference.name}"))
        setSyncIntervalHours(domain.syncIntervalHours)
        setLastSyncTimestamp(domain.lastSyncTimestamp)
        setMaxHeartRate(domain.maxHeartRate)
        setAutoCalculateMaxHr(domain.autoCalculateMaxHr)
        setManualZoneEditing(domain.manualZoneEditing)
        setBackgroundSyncEnabled(domain.backgroundSyncEnabled)
        setBackgroundSyncIntervalMinutes(domain.backgroundSyncIntervalMinutes)
        domain.hrvBaselineOverride?.let { setHrvBaselineOverride(it) }
        domain.rhrBaselineOverride?.let { setRhrBaselineOverride(it) }
    }

internal fun UserPreferencesProto.Builder.applyZoneAndDemographicFields(
    domain: UserPreferences,
): UserPreferencesProto.Builder =
    apply {
        setZone1MinPercent(domain.zone1MinPercent)
        setZone1MaxPercent(domain.zone1MaxPercent)
        setZone2MaxPercent(domain.zone2MaxPercent)
        setZone3MaxPercent(domain.zone3MaxPercent)
        setZone4MaxPercent(domain.zone4MaxPercent)
        setZone1MinBpm(domain.zone1MinBpm)
        setZone1MaxBpm(domain.zone1MaxBpm)
        setZone2MaxBpm(domain.zone2MaxBpm)
        setZone3MaxBpm(domain.zone3MaxBpm)
        setZone4MaxBpm(domain.zone4MaxBpm)
        setAge(domain.age)
        if (domain.birthDate != null) {
            try {
                val date = LocalDate.parse(domain.birthDate)
                setBirthDay(date.dayOfMonth)
                setBirthMonth(date.monthValue)
                setBirthYear(date.year)
            } catch (_: DateTimeParseException) {
                // Malformed stored birthDate: keep the proto defaults.
            }
        }
        domain.gender?.let { setGender(it.name) }
        domain.heightCm?.let { setHeightCm(it) }
        setIsBirthdayConfigured(domain.isBirthdayConfigured)
        setUnitSystem(
            when (domain.unitSystem) {
                UnitSystem.METRIC -> UnitSystemProto.UNIT_METRIC
                UnitSystem.IMPERIAL -> UnitSystemProto.UNIT_IMPERIAL
            },
        )
        setWeekStartDay(domain.weekStartDay.toProto())
    }

internal fun UserPreferencesProto.Builder.applyThresholdAndDisplayFields(
    domain: UserPreferences,
): UserPreferencesProto.Builder =
    apply {
        setHrvOptimalThreshold(domain.hrvOptimalThreshold)
        setHrvWarningThreshold(domain.hrvWarningThreshold)
        setRhrOptimalThreshold(domain.rhrOptimalThreshold)
        setRhrWarningThreshold(domain.rhrWarningThreshold)
        setRestingHrPercentile(domain.restingHrPercentile)
        setAppTheme(AppThemeProto.valueOf("THEME_${domain.appTheme.name}"))
        setDynamicColorEnabled(domain.dynamicColorEnabled)
        setFallbackThemeColor(domain.fallbackThemeColor.toProto())
        setBackupSchedule(BackupScheduleProto.valueOf("BACKUP_${domain.backupSchedule.name}"))
        setLastBackupTimestamp(domain.lastBackupTimestamp)
        setConsistencyThresholdMinutes(domain.consistencyThresholdMinutes)
        setConsistencyEvaluationDays(domain.consistencyEvaluationDays)
        setConsistencyBaselineDays(domain.consistencyBaselineDays)
        setHrrToleranceSeconds(domain.hrrToleranceSeconds)
        setRasScalingFactor(domain.rasScalingFactor)
        setStepGoal(domain.stepGoal)
        setRetentionDaysEnabled(domain.retentionDaysEnabled)
        setRetentionDays(domain.retentionDays)
        setBodyTempElevatedThresholdCelsius(domain.bodyTempElevatedThresholdCelsius)
        domain.circadianThresholdOverride?.let { setCircadianThresholdOverride(it) }
    }

internal fun UserPreferencesProto.Builder.applyPaletteAndUiFields(
    domain: UserPreferences,
): UserPreferencesProto.Builder =
    apply {
        setCollapseHealthConnect(domain.collapseHealthConnect)
        setCollapseBaselinesThresholds(domain.collapseBaselinesThresholds)
        setCollapseDisplay(domain.collapseDisplay)
        setCollapseAdvanced(domain.collapseAdvanced)
        setAboutDismissed(domain.aboutDismissed)
        setPhysiologyProfile(PhysiologyProfileProto.valueOf("PROFILE_${domain.physiologyProfile.name}"))
        setInstallDate(domain.installDate)
        setIsCustomPaletteEnabled(domain.isCustomPaletteEnabled)
        setCustomSecondaryColor(domain.customSecondaryColor)
        setCustomTertiaryColor(domain.customTertiaryColor)
        setCustomPrimaryColor(domain.customPrimaryColor)
        domain.primaryDeviceName?.let { setPrimaryDeviceName(it) }
        domain.backupDirectoryUri?.let { setBackupDirectoryUri(it) }
        domain.backupPasswordHash?.let { setBackupPasswordHash(it) }
        setScoringZoneId(domain.scoringZoneId)
        setLastGlobalDisplayMode(
            when (domain.lastGlobalDisplayMode) {
                DashboardCardDisplayMode.VALUE -> DashboardCardDisplayModeProto.DASHBOARD_CARD_DISPLAY_MODE_VALUE
                DashboardCardDisplayMode.GAUGE -> DashboardCardDisplayModeProto.DASHBOARD_CARD_DISPLAY_MODE_GAUGE
                DashboardCardDisplayMode.BAR -> DashboardCardDisplayModeProto.DASHBOARD_CARD_DISPLAY_MODE_BAR
                null -> DashboardCardDisplayModeProto.DASHBOARD_CARD_DISPLAY_MODE_UNSET
            },
        )
    }

internal fun UserPreferencesProto.Builder.applyScoringAndRecalcFields(
    domain: UserPreferences,
): UserPreferencesProto.Builder =
    apply {
        setTrimpMethod(
            when (domain.trimpModel) {
                app.readylytics.health.core.model.domain.scoring.TrimpModel.BANISTER -> TrimpMethodProto.TRIMP_BANISTER
                app.readylytics.health.core.model.domain.scoring.TrimpModel.I_TRIMP -> TrimpMethodProto.TRIMP_ITRIMP
                app.readylytics.health.core.model.domain.scoring.TrimpModel.CHENG -> TrimpMethodProto.TRIMP_CHENG
            },
        )
        setRasCalibration(domain.banisterMultiplier)
        setChengBeta(domain.chengBeta)
        setItrimpB(domain.itrimB)
        setStrainLoadSourceMode(
            when (domain.strainLoadSourceMode) {
                LoadSourceMode.WORKOUT_ONLY -> LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY
                LoadSourceMode.EVERYDAY_HEART_RATE -> LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE
            },
        )
        setRasSourceMode(
            when (domain.rasSourceMode) {
                LoadSourceMode.WORKOUT_ONLY -> LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY
                LoadSourceMode.EVERYDAY_HEART_RATE -> LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE
            },
        )
        setSleepScoreWeightProfile(mapSleepScoreWeightProfile(domain.sleepScoreWeightProfile))
        setHypersomniaOnsetPercent(domain.hypersomniaOnsetPercent)
        domain.lastRecalcSleepScoreWeightProfile?.let {
            setLastRecalcSleepScoreWeightProfile(mapSleepScoreWeightProfile(it))
        }
        domain.lastRecalcGoalSleepHours?.let { setLastRecalcGoalSleepHours(it) }
        domain.lastRecalcHypersomniaOnsetPercent?.let { setLastRecalcHypersomniaOnsetPercent(it) }
        setScoringVersion(domain.scoringVersion)
        setTrimpNormalizationMigrated(domain.trimpNormalizationMigrated)
        setResidualFatigueEnabled(domain.residualFatigueEnabled)
        setResidualFatigueHalfLifeHours(domain.residualFatigueHalfLifeHours)
        setResidualFatigueGain(domain.residualFatigueGain)
    }

private fun mapSleepScoreWeightProfile(profile: SleepScoreWeightProfile): SleepScoreWeightProfileProto =
    when (profile) {
        SleepScoreWeightProfile.BALANCED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_BALANCED
        SleepScoreWeightProfile.DURATION_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_DURATION_FOCUSED
        SleepScoreWeightProfile.RECOVERY_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_RECOVERY_FOCUSED
        SleepScoreWeightProfile.ARCHITECTURE_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_ARCHITECTURE_FOCUSED
        SleepScoreWeightProfile.CONTINUITY_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_CONTINUITY_FOCUSED
    }
