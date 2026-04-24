package com.gregor.lauritz.healthdashboard.data.preferences

enum class BackupSchedule { MANUAL, DAILY, WEEKLY }

data class UserPreferences(
    val goalSleepHours: Float = 8f,
    val hrvBaselineOverride: Float? = null,
    val rhrBaselineOverride: Float? = null,
    val syncPreference: SyncPreference = SyncPreference.BY_TIME,
    val syncIntervalHours: Int = 1,
    val lastSyncTimestamp: Long = 0L,
    val maxHeartRate: Int = 190,
    val hrvOptimalThreshold: Float = 1.00f,
    val hrvWarningThreshold: Float = 0.90f,
    val rhrOptimalThreshold: Float = 0.95f,
    val rhrWarningThreshold: Float = 1.05f,
    val restingHrBeforeMinutes: Int = 5,
    val restingHrAfterMinutes: Int = 15,
    val appTheme: AppTheme = AppTheme.SYSTEM,
    val driveAccountEmail: String? = null,
    val backupSchedule: BackupSchedule = BackupSchedule.MANUAL,
    val lastBackupTimestamp: Long = 0L,
)
