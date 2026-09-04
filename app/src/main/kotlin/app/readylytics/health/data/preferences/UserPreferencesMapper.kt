package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.SyncPreference
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.data.preferences.normalizeCoreMergeGapMinutes
import app.readylytics.health.core.model.data.preferences.normalizeMinimumCountedSleepSegmentMinutes
import app.readylytics.health.core.model.data.preferences.normalizeSupplementalArchitectureCoveragePercent
import app.readylytics.health.core.model.data.preferences.normalizeSupplementalCutoffMinutesOfDay
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile

fun PhysiologyProfileProto.toDomainProfile(): PhysiologyProfile =
    when (this) {
        PhysiologyProfileProto.PROFILE_ATHLETE -> PhysiologyProfile.ATHLETE
        PhysiologyProfileProto.PROFILE_SEDENTARY -> PhysiologyProfile.SEDENTARY
        else -> PhysiologyProfile.ACTIVE
    }

fun SleepScoreWeightProfileProto.toDomainProfile(): SleepScoreWeightProfile =
    when (this) {
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_DURATION_FOCUSED ->
            SleepScoreWeightProfile.DURATION_FOCUSED
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_RECOVERY_FOCUSED ->
            SleepScoreWeightProfile.RECOVERY_FOCUSED
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_ARCHITECTURE_FOCUSED ->
            SleepScoreWeightProfile.ARCHITECTURE_FOCUSED
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_CONTINUITY_FOCUSED ->
            SleepScoreWeightProfile.CONTINUITY_FOCUSED
        else -> SleepScoreWeightProfile.BALANCED
    }

fun Vo2MaxSourceModeProto.toDomainMode(): Vo2MaxSourceMode =
    when (this) {
        Vo2MaxSourceModeProto.VO2_MAX_SOURCE_WEARABLE_ONLY -> Vo2MaxSourceMode.WEARABLE_ONLY
        Vo2MaxSourceModeProto.VO2_MAX_SOURCE_ESTIMATED_ONLY -> Vo2MaxSourceMode.ESTIMATED_ONLY
        else -> Vo2MaxSourceMode.AUTO
    }

fun UserPreferencesProto.toDomainModel(): UserPreferences {
    val profile = physiologyProfile.toDomainProfile()
    return toSyncAndBaselinePreferences(profile)
        .withZonesAndDemographics(this)
        .withThresholdsAndDisplay(this)
        .withPaletteAndUi(this)
        .withDevicesAndBackups(this)
        .withScoringProfiles(this, profile)
        .withResidualFatigueAndTrainingReadiness(this)
        .withRecalcAndVersion(this)
}

private fun UserPreferencesProto.toSyncAndBaselinePreferences(profile: PhysiologyProfile): UserPreferences =
    UserPreferences(
        goalSleepHours = goalSleepHours,
        coreMergeGapMinutes =
            if (hasCoreMergeGapMinutes()) {
                normalizeCoreMergeGapMinutes(coreMergeGapMinutes)
            } else {
                SettingsDefaults.CORE_MERGE_GAP_MINUTES
            },
        supplementalCutoffMinutesOfDay =
            if (hasSupplementalCutoffMinutesOfDay()) {
                normalizeSupplementalCutoffMinutesOfDay(supplementalCutoffMinutesOfDay)
            } else {
                SettingsDefaults.SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY
            },
        minimumCountedSleepSegmentMinutes =
            if (hasMinimumCountedSleepSegmentMinutes()) {
                normalizeMinimumCountedSleepSegmentMinutes(minimumCountedSleepSegmentMinutes)
            } else {
                SettingsDefaults.MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES
            },
        supplementalArchitectureCoveragePercent =
            if (hasSupplementalArchitectureCoveragePercent()) {
                normalizeSupplementalArchitectureCoveragePercent(supplementalArchitectureCoveragePercent)
            } else {
                SettingsDefaults.SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT
            },
        hrvBaselineOverride = if (hasHrvBaselineOverride()) hrvBaselineOverride else null,
        rhrBaselineOverride = if (hasRhrBaselineOverride()) rhrBaselineOverride else null,
        syncPreference = SyncPreference.valueOf(syncPreference.name.removePrefix("SYNC_")),
        syncIntervalHours = syncIntervalHours,
        lastSyncTimestamp = lastSyncTimestamp,
        backgroundSyncEnabled = backgroundSyncEnabled,
        backgroundSyncIntervalMinutes =
            if (backgroundSyncIntervalMinutes == 0) {
                SettingsDefaults.BACKGROUND_SYNC_INTERVAL.minutes
            } else {
                backgroundSyncIntervalMinutes
            },
        maxHeartRate = maxHeartRate,
        autoCalculateMaxHr = autoCalculateMaxHr,
        manualZoneEditing = manualZoneEditing,
        physiologyProfile = profile,
    )
