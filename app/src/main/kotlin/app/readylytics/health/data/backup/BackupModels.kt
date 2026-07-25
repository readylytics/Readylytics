package app.readylytics.health.data.backup

import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.domain.dashboard.CardConfiguration
import kotlinx.serialization.Serializable

internal object BackupSchemaPolicy {
    const val MIN_SUPPORTED_VERSION = 5
    const val MAX_SUPPORTED_VERSION = 7

    fun requireSupported(version: Int) {
        require(version in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
            "Unsupported backup schema version $version; supported range is " +
                "$MIN_SUPPORTED_VERSION..$MAX_SUPPORTED_VERSION"
        }
    }
}

@Serializable
data class BackupManifest(
    val schemaVersion: Int,
    val exportedAt: String,
    val rowCounts: Map<String, Int>,
)

@Serializable
internal data class LegacyHeartRateRecordBackup(
    val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    fun toCurrent() =
        HeartRateRecordEntity(
            sourceRecordId = legacySourceRecordId(id, timestampMs),
            timestampMs = timestampMs,
            beatsPerMinute = beatsPerMinute,
            recordType = recordType,
            sessionId = sessionId,
            deviceName = deviceName,
        )
}

@Serializable
internal data class LegacyHrvRecordBackup(
    val id: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    fun toCurrent() =
        HrvRecordEntity(
            sourceRecordId = legacySourceRecordId(id, timestampMs),
            timestampMs = timestampMs,
            rmssdMs = rmssdMs,
            recordType = recordType,
            sessionId = sessionId,
            deviceName = deviceName,
        )
}

internal fun legacySourceRecordId(
    id: String,
    timestampMs: Long,
): String {
    val suffix = "_$timestampMs"
    return if (id.endsWith(suffix) && id.length > suffix.length) {
        id.dropLast(suffix.length)
    } else {
        id
    }
}

@Serializable
data class UserPreferencesBackup(
    val goalSleepHours: Float? = null,
    val hrvBaselineOverride: Float? = null,
    val rhrBaselineOverride: Float? = null,
    val syncPreference: String? = null,
    val syncIntervalHours: Int? = null,
    val backgroundSyncEnabled: Boolean? = null,
    val backgroundSyncIntervalMinutes: Int? = null,
    val lastSyncTimestamp: Long? = null,
    val maxHeartRate: Int? = null,
    val autoCalculateMaxHr: Boolean? = null,
    val manualZoneEditing: Boolean? = null,
    val zone1MinPercent: Float? = null,
    val zone1MaxPercent: Float? = null,
    val zone2MaxPercent: Float? = null,
    val zone3MaxPercent: Float? = null,
    val zone4MaxPercent: Float? = null,
    val zone1MinBpm: Int? = null,
    val zone1MaxBpm: Int? = null,
    val zone2MaxBpm: Int? = null,
    val zone3MaxBpm: Int? = null,
    val zone4MaxBpm: Int? = null,
    val age: Int? = null,
    val birthDate: String? = null,
    val birthDay: Int? = null,
    val birthMonth: Int? = null,
    val birthYear: Int? = null,
    val gender: String? = null,
    val heightCm: Float? = null,
    val hrvOptimalThreshold: Float? = null,
    val hrvWarningThreshold: Float? = null,
    val rhrOptimalThreshold: Float? = null,
    val rhrWarningThreshold: Float? = null,
    val hrrToleranceSeconds: Int? = null,
    val restingHrBeforeMinutes: Int? = null,
    val restingHrAfterMinutes: Int? = null,
    val appTheme: String? = null,
    val backupSchedule: String? = null,
    val lastBackupTimestamp: Long? = null,
    val consistencyThresholdMinutes: Int? = null,
    val consistencyEvaluationDays: Int? = null,
    val consistencyBaselineDays: Int? = null,
    val rasScalingFactor: Float? = null,
    val paiScalingFactor: Float? = null,
    val stepGoal: Int? = null,
    val retentionDaysEnabled: Boolean? = null,
    val retentionDays: Int? = null,
    val collapseHealthConnect: Boolean? = null,
    val collapseBaselinesThresholds: Boolean? = null,
    val collapseDisplay: Boolean? = null,
    val collapseAdvanced: Boolean? = null,
    val aboutDismissed: Boolean? = null,
    val physiologyProfile: String? = null,
    val installDate: Long? = null,
    val circadianThresholdOverride: String? = null,
    val dynamicColorEnabled: Boolean? = null,
    val trimpModel: String? = null,
    val banisterMultiplier: Float? = null,
    val chengBeta: Float? = null,
    val itrimB: Float? = null,
    val primaryDeviceName: String? = null,
    val deviceByDataType: Map<String, String>? = null,
    val backupDirectoryUri: String? = null,
    val dashboardCards: List<CardConfiguration>? = null,
)
