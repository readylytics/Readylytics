package app.readylytics.health.data.backup

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.util.logW
import app.readylytics.health.data.preferences.AppThemeProto
import app.readylytics.health.data.preferences.BackupScheduleProto
import app.readylytics.health.data.preferences.PhysiologyProfileProto
import app.readylytics.health.data.preferences.SleepScoreWeightProfileProto
import app.readylytics.health.data.preferences.SyncPreferenceProto
import app.readylytics.health.data.preferences.TrimpMethodProto
import app.readylytics.health.data.preferences.UserPreferencesProto

private fun hasCompleteZonePercentSettings(backup: UserPreferencesBackup) =
    backup.zone1MinPercent != null &&
        backup.zone1MaxPercent != null &&
        backup.zone2MaxPercent != null &&
        backup.zone3MaxPercent != null &&
        backup.zone4MaxPercent != null

private fun hasCompleteZoneBpmSettings(backup: UserPreferencesBackup) =
    backup.zone1MinBpm != null &&
        backup.zone1MaxBpm != null &&
        backup.zone2MaxBpm != null &&
        backup.zone3MaxBpm != null &&
        backup.zone4MaxBpm != null

internal fun UserPreferencesProto.Builder.applyZoneSettings(backup: UserPreferencesBackup) {
    if (hasCompleteZonePercentSettings(backup)) {
        zone1MinPercent = backup.zone1MinPercent!!
        zone1MaxPercent = backup.zone1MaxPercent!!
        zone2MaxPercent = backup.zone2MaxPercent!!
        zone3MaxPercent = backup.zone3MaxPercent!!
        zone4MaxPercent = backup.zone4MaxPercent!!
    }
    if (hasCompleteZoneBpmSettings(backup)) {
        zone1MinBpm = backup.zone1MinBpm!!
        zone1MaxBpm = backup.zone1MaxBpm!!
        zone2MaxBpm = backup.zone2MaxBpm!!
        zone3MaxBpm = backup.zone3MaxBpm!!
        zone4MaxBpm = backup.zone4MaxBpm!!
    }
}

internal fun UserPreferencesProto.Builder.applySyncAndBaselineSettings(backup: UserPreferencesBackup) {
    backup.goalSleepHours?.let { goalSleepHours = it }
    if (backup.hrvBaselineOverride !=
        null
    ) {
        hrvBaselineOverride = backup.hrvBaselineOverride
    } else {
        clearHrvBaselineOverride()
    }
    if (backup.rhrBaselineOverride !=
        null
    ) {
        rhrBaselineOverride = backup.rhrBaselineOverride
    } else {
        clearRhrBaselineOverride()
    }

    backup.syncPreference?.let { raw ->
        val resolved = resolveProtoEnum(raw, "SYNC_", SyncPreferenceProto::valueOf)
        if (resolved != null) {
            syncPreference = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised sync preference '$raw' in backup settings" }
        }
    }
    backup.syncIntervalHours?.let { syncIntervalHours = it }
    backup.backgroundSyncEnabled?.let { backgroundSyncEnabled = it }
    backup.backgroundSyncIntervalMinutes?.let { backgroundSyncIntervalMinutes = it }
    backup.lastSyncTimestamp?.let { lastSyncTimestamp = it }
    backup.maxHeartRate?.let { maxHeartRate = it }
    backup.autoCalculateMaxHr?.let { autoCalculateMaxHr = it }
    backup.manualZoneEditing?.let { manualZoneEditing = it }
}

internal fun UserPreferencesProto.Builder.applyBirthdaySettings(backup: UserPreferencesBackup) {
    backup.age?.let { age = it }
    val parsedDate =
        backup.birthDate?.let {
            try {
                java.time.LocalDate.parse(it)
            } catch (e: java.time.format.DateTimeParseException) {
                null
            }
        }
    if (parsedDate != null) {
        birthDay = parsedDate.dayOfMonth
        birthMonth = parsedDate.monthValue
        birthYear = parsedDate.year
        isBirthdayConfigured = true
    } else if (backup.birthDay != null && backup.birthMonth != null && backup.birthYear != null) {
        birthDay = backup.birthDay
        birthMonth = backup.birthMonth
        birthYear = backup.birthYear
        isBirthdayConfigured = true
    }
}

internal fun UserPreferencesProto.Builder.applyDemographicAndThresholdSettings(backup: UserPreferencesBackup) {
    backup.gender?.let { gender = it } ?: clearGender()
    backup.heightCm?.let { heightCm = it } ?: clearHeightCm()
    backup.hrvOptimalThreshold?.let { hrvOptimalThreshold = it }
    backup.hrvWarningThreshold?.let { hrvWarningThreshold = it }
    backup.rhrOptimalThreshold?.let { rhrOptimalThreshold = it }
    backup.rhrWarningThreshold?.let { rhrWarningThreshold = it }
    backup.hrrToleranceSeconds?.let {
        hrrToleranceSeconds =
            it.coerceIn(
                SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS,
                SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS,
            )
    }
    backup.restingHrBeforeMinutes?.let { restingHrBeforeMinutes = it }
    backup.restingHrAfterMinutes?.let { restingHrAfterMinutes = it }
}

internal fun UserPreferencesProto.Builder.applyThemeAndBackupSettings(backup: UserPreferencesBackup) {
    backup.appTheme?.let { raw ->
        val resolved = resolveProtoEnum(raw, "THEME_", AppThemeProto::valueOf)
        if (resolved != null) {
            appTheme = resolved
        } else {
            logW("RestorePreferencesApplier") { "Ignoring unrecognised app theme '$raw' in backup settings" }
        }
    }

    backup.backupSchedule?.let { raw ->
        val resolved = resolveProtoEnum(raw, "BACKUP_", BackupScheduleProto::valueOf)
        if (resolved != null) {
            backupSchedule = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised backup schedule '$raw' in backup settings" }
        }
    }

    backup.lastBackupTimestamp?.let { lastBackupTimestamp = it }
    backup.consistencyThresholdMinutes?.let { consistencyThresholdMinutes = it }
    backup.consistencyEvaluationDays?.let { consistencyEvaluationDays = it }
    backup.consistencyBaselineDays?.let { consistencyBaselineDays = it }
}

internal fun UserPreferencesProto.Builder.applyScalingAndProfileSettings(backup: UserPreferencesBackup) {
    (backup.rasScalingFactor ?: backup.paiScalingFactor)?.let { rasScalingFactor = it }
    backup.stepGoal?.let { stepGoal = it }
    backup.retentionDaysEnabled?.let { retentionDaysEnabled = it }
    backup.retentionDays?.let { retentionDays = it }
    backup.collapseHealthConnect?.let { collapseHealthConnect = it }
    backup.collapseBaselinesThresholds?.let { collapseBaselinesThresholds = it }
    backup.collapseDisplay?.let { collapseDisplay = it }
    backup.collapseAdvanced?.let { collapseAdvanced = it }
    backup.aboutDismissed?.let { aboutDismissed = it }

    backup.physiologyProfile?.let { raw ->
        val resolved = resolveProtoEnum(raw, "PROFILE_", PhysiologyProfileProto::valueOf)
        if (resolved != null) {
            physiologyProfile = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised physiology profile '$raw' in backup settings" }
        }
    }
}

internal fun UserPreferencesProto.Builder.applyDeviceSettings(backup: UserPreferencesBackup) {
    backup.installDate?.let { installDate = it }
    backup.circadianThresholdOverride?.let { circadianThresholdOverride = it }
        ?: clearCircadianThresholdOverride()
    backup.dynamicColorEnabled?.let { dynamicColorEnabled = it }
    backup.primaryDeviceName?.let { primaryDeviceName = it }
    backup.deviceByDataType?.let { putAllDeviceByDataType(it) }
    backup.backupDirectoryUri?.let { backupDirectoryUri = it }
}

internal fun UserPreferencesProto.Builder.applyScoringSettings(backup: UserPreferencesBackup) {
    backup.banisterMultiplier?.let { rasCalibration = it }

    backup.trimpModel?.let {
        try {
            trimpMethod = TrimpMethodProto.valueOf(it)
        } catch (e: IllegalArgumentException) {
            logW("RestorePreferencesApplier", e) { "Ignoring invalid trimp model in backup settings" }
        }
    }

    backup.chengBeta?.let { chengBeta = it }
    backup.itrimB?.let { itrimpB = it }
    backup.sleepScoreWeightProfile?.let { raw ->
        val resolved = resolveProtoEnum(raw, "SLEEP_WEIGHT_PROFILE_", SleepScoreWeightProfileProto::valueOf)
        if (resolved != null) {
            sleepScoreWeightProfile = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised sleep score weight profile '$raw' in backup settings" }
        }
    }
    backup.hypersomniaOnsetPercent?.let { hypersomniaOnsetPercent = it }
    backup.scoringVersion?.let { scoringVersion = it }
    backup.lastRecalcSleepScoreWeightProfile?.let { raw ->
        val resolved =
            resolveProtoEnum(raw, "SLEEP_WEIGHT_PROFILE_", SleepScoreWeightProfileProto::valueOf)
        if (resolved != null) {
            lastRecalcSleepScoreWeightProfile = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised lastRecalcSleepScoreWeightProfile '$raw' in backup settings" }
        }
    }
}
