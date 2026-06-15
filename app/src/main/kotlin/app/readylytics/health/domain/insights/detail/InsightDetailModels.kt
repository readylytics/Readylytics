package app.readylytics.health.domain.insights.detail

import app.readylytics.health.domain.model.InsightType

enum class InsightDetailType {
    PHYSIOLOGY,
    TRAINING_BEHAVIOR,
    DATA_QUALITY,
}

enum class InsightConfidence {
    LOW,
    LOW_MEDIUM,
    MEDIUM,
    MEDIUM_HIGH,
    HIGH,
}

enum class CauseRankHint {
    GENERIC,
    LATE_WORKOUT,
    HIGH_STRAIN_RATIO,
    HIGH_TRIMP_YESTERDAY,
    POOR_SLEEP,
    LOW_HRV,
    VERY_LOW_HRV,
    ELEVATED_RHR,
    STRONG_ELEVATED_RHR,
    LOW_SPO2,
    LARGE_BEDTIME_SHIFT,
    LOW_ACTIVITY,
}

data class InsightCause(
    val title: String,
    val description: String,
    val rankHint: CauseRankHint = CauseRankHint.GENERIC,
)

data class InsightDetailContent(
    val id: InsightType,
    val type: InsightDetailType,
    val title: String,
    val cardDescription: String,
    val observedSignalTitle: String,
    val observedSignal: String,
    val meaningTitle: String?,
    val meaning: String?,
    val confidence: InsightConfidence?,
    val causesTitle: String,
    val causes: List<InsightCause>,
    val recommendationsTitle: String,
    val recommendations: List<String>,
    val caveatsTitle: String?,
    val caveats: List<String>,
    val safetyNote: String?,
)
