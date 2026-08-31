package app.readylytics.health.data.backup

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepChartConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemConfiguration
import kotlinx.serialization.Serializable

internal object BackupSchemaPolicy {
    const val MIN_SUPPORTED_VERSION = 5

    // Tracks HealthDatabase.DATABASE_VERSION directly (both are compile-time constants, so this
    // const-folds) instead of hardcoding a literal that must be remembered on every schema bump.
    // LocalBackupManager always stamps a fresh backup's schemaVersion with the current
    // DATABASE_VERSION, so "max supported" and "current DB version" must never drift apart.
    const val MAX_SUPPORTED_VERSION = HealthDatabase.DATABASE_VERSION

    // Fixed historical fact: heart-rate/HRV records adopted the current sourceRecordId-based
    // entity format at the external v6->v7 SQLCipher migration (V7DatabaseMigrator). This does
    // NOT move as MAX_SUPPORTED_VERSION grows with later schema bumps (e.g. this task's v7->v8)
    // — do not couple it to MAX_SUPPORTED_VERSION or a backup produced at any version >= 7 will
    // be mis-decoded via the legacy id-suffix path once a later DATABASE_VERSION ships.
    const val CURRENT_RECORD_FORMAT_MIN_VERSION = 7

    // At DATABASE_VERSION 10 heart-rate/HRV records moved to an integer sourceRecordRef FK into
    // health_source_records. Backups stamped with a schema < 10 serialize the legacy TEXT
    // sourceRecordId; >= 10 serialize the integer ref directly (and carry a health_source_records
    // table alongside). Restore picks the decode path from this boundary.
    const val SOURCE_REF_FORMAT_MIN_VERSION = 10

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
    fun toSourceRecordId(): String = legacySourceRecordId(id, timestampMs)
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
    fun toSourceRecordId(): String = legacySourceRecordId(id, timestampMs)
}

// Backups stamped in [BackupSchemaPolicy.CURRENT_RECORD_FORMAT_MIN_VERSION]..9 carry the TEXT
// sourceRecordId (`<baseUuid>_<timestampMs>`); restore recovers the base UUID and re-resolves an
// integer sourceRecordRef against the restored health_source_records table.
@Serializable
internal data class SourceRecordIdHeartRateRecordBackup(
    val sourceRecordId: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

@Serializable
internal data class SourceRecordIdHrvRecordBackup(
    val sourceRecordId: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

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
    val vitalsCards: List<CardConfiguration>? = null,
    val vitalsCharts: List<VitalsChartConfiguration>? = null,
    val sleepTopCards: List<SleepTopCardConfiguration>? = null,
    val sleepCharts: List<SleepChartConfiguration>? = null,
    val sleepMetricCards: List<SleepMetricCardConfiguration>? = null,
    val workoutCards: List<CardConfiguration>? = null,
    val workoutCharts: List<WorkoutChartConfiguration>? = null,
    val workoutHistory: List<WorkoutHistoryConfiguration>? = null,
    val workoutDetailLayouts: Map<String, List<WorkoutDetailItemConfiguration>>? = null,
    val sleepScoreWeightProfile: String? = null,
    val hypersomniaOnsetPercent: Int? = null,
    val scoringVersion: Int? = null,
    val lastRecalcSleepScoreWeightProfile: String? = null,
    val lastRecalcGoalSleepHours: Float? = null,
    val lastRecalcHypersomniaOnsetPercent: Int? = null,
)
