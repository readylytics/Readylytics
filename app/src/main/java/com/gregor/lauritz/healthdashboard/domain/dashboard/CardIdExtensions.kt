package com.gregor.lauritz.healthdashboard.domain.dashboard

fun CardId.displayName(): String = when (this) {
    CardId.SLEEP_SCORE -> "Sleep Score"
    CardId.READINESS -> "Readiness"
    CardId.STEPS -> "Steps"
    CardId.HRV -> "Heart Rate Variability"
    CardId.SLEEP_RHR -> "Sleep RHR"
    CardId.SLEEP_DURATION -> "Sleep Duration"
    CardId.SLEEP_ARCHITECTURE -> "Sleep Architecture"
    CardId.STRAIN_RATIO -> "Strain Ratio"
    CardId.PAI_DAILY -> "PAI Daily"
    CardId.CIRCADIAN_CONSISTENCY -> "Circadian Consistency"
    CardId.RESTING_HR -> "Resting HR"
    CardId.RECOVERY_INDEX -> "Recovery Index"
    CardId.ACUTE_CHRONIC_RATIO -> "Acute/Chronic Ratio"
    CardId.SLEEP_EFFICIENCY -> "Sleep Efficiency"
}
