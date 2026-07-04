package app.readylytics.health.domain.model

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

fun BmiStatus.toMetricStatus(): MetricStatus =
    when (this) {
        BmiStatus.Optimal -> MetricStatus.OPTIMAL
        BmiStatus.Neutral -> MetricStatus.NEUTRAL
        BmiStatus.Warning -> MetricStatus.WARNING
        BmiStatus.Poor -> MetricStatus.POOR
    }
