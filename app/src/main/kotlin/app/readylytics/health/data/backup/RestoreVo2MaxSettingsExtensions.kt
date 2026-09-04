package app.readylytics.health.data.backup

import app.readylytics.health.core.model.domain.util.logW
import app.readylytics.health.data.preferences.UserPreferencesProto
import app.readylytics.health.data.preferences.Vo2MaxEstimationMethodProto
import app.readylytics.health.data.preferences.Vo2MaxSourceModeProto

internal fun UserPreferencesProto.Builder.applyVo2MaxSettings(backup: UserPreferencesBackup) {
    backup.vo2MaxEstimationMethod?.let { raw ->
        val resolved = resolveProtoEnum(raw, "VO2_MAX_METHOD_", Vo2MaxEstimationMethodProto::valueOf)
        if (resolved != null) {
            vo2MaxEstimationMethod = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised vo2MaxEstimationMethod '$raw' in backup settings" }
        }
    }
    backup.vo2MaxSourceMode?.let { raw ->
        val resolved = resolveProtoEnum(raw, "VO2_MAX_SOURCE_", Vo2MaxSourceModeProto::valueOf)
        if (resolved != null) {
            vo2MaxSourceMode = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised vo2MaxSourceMode '$raw' in backup settings" }
        }
    }
}
