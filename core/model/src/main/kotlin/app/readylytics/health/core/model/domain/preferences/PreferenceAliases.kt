package app.readylytics.health.core.model.domain.preferences

import java.time.ZoneId

typealias AppTheme = app.readylytics.health.core.model.data.preferences.AppTheme
typealias BackupSchedule = app.readylytics.health.core.model.data.preferences.BackupSchedule
typealias Gender = app.readylytics.health.core.model.data.preferences.Gender
typealias PhysiologyProfile = app.readylytics.health.core.model.data.preferences.PhysiologyProfile
typealias SettingsDefaults = app.readylytics.health.core.model.data.preferences.SettingsDefaults
typealias SyncPreference = app.readylytics.health.core.model.data.preferences.SyncPreference
typealias UnitSystem = app.readylytics.health.core.model.data.preferences.UnitSystem
typealias UserPreferences = app.readylytics.health.core.model.data.preferences.UserPreferences

fun UserPreferences.scoringZone(): ZoneId =
    scoringZoneId
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
