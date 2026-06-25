package app.readylytics.health.domain.preferences

import java.time.ZoneId

typealias AppTheme = app.readylytics.health.data.preferences.AppTheme
typealias BackupSchedule = app.readylytics.health.data.preferences.BackupSchedule
typealias CardConfigurationRepository = app.readylytics.health.data.preferences.CardConfigurationRepository
typealias Gender = app.readylytics.health.data.preferences.Gender
typealias PhysiologyProfile = app.readylytics.health.data.preferences.PhysiologyProfile
typealias SettingsDefaults = app.readylytics.health.data.preferences.SettingsDefaults
typealias SettingsRepository = app.readylytics.health.data.preferences.SettingsRepository
typealias SyncPreference = app.readylytics.health.data.preferences.SyncPreference
typealias UnitSystem = app.readylytics.health.data.preferences.UnitSystem
typealias UserPreferences = app.readylytics.health.data.preferences.UserPreferences

fun UserPreferences.scoringZone(): ZoneId =
    scoringZoneId
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
