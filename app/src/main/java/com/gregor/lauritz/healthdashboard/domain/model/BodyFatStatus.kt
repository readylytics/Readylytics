package com.gregor.lauritz.healthdashboard.domain.model

sealed class BodyFatStatus {
    object Optimal : BodyFatStatus()
    object Neutral : BodyFatStatus()
    object Poor : BodyFatStatus()
    object Calibrating : BodyFatStatus()

    val displayName: String
        get() =
            when (this) {
                Optimal -> "Optimal"
                Neutral -> "Acceptable"
                Poor -> "High"
                Calibrating -> "Calibrating"
            }
}
