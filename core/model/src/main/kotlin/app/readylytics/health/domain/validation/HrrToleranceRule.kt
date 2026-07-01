package app.readylytics.health.domain.validation

import app.readylytics.health.data.preferences.SettingsDefaults

class HrrToleranceRule : IntRangeRule(
    SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS,
    SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS,
    "Recovery match window: ${SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS}–${SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS} s",
)
