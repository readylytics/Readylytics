package com.gregor.lauritz.healthdashboard.domain.model

sealed class BmiStatus {
    object Optimal : BmiStatus()

    object Neutral : BmiStatus()

    object Warning : BmiStatus()

    object Poor : BmiStatus()

    val displayName: String
        get() =
            when (this) {
                Optimal -> "Normal"
                Neutral -> "Overweight"
                Warning -> "Obese Class I"
                Poor -> "Obese Class II+"
            }
}
