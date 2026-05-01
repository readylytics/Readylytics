package com.gregor.lauritz.healthdashboard.domain.scoring.components

data class RestorationWeights(
    val hrvWeight: Float,
    val rhrWeight: Float,
) {
    init {
        require(hrvWeight >= 0f && rhrWeight >= 0f) { "Weights must be non-negative" }
        require((hrvWeight + rhrWeight) > 0f) { "At least one weight must be non-zero" }
    }

    val hrvPercentage: Float
        get() = if (hrvWeight + rhrWeight == 0f) 0f else hrvWeight / (hrvWeight + rhrWeight)

    val rhrPercentage: Float
        get() = if (hrvWeight + rhrWeight == 0f) 0f else rhrWeight / (hrvWeight + rhrWeight)
}
