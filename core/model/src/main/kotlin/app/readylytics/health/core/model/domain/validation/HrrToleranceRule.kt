package app.readylytics.health.core.model.domain.validation

import app.readylytics.health.core.model.data.preferences.SettingsDefaults

class HrrToleranceRule : IntRangeRule(
    SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS,
    SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS,
    "Recovery match window: ${SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS}–${SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS} s",
)
