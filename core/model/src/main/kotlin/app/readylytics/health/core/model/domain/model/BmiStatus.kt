package app.readylytics.health.core.model.domain.model

sealed class BmiStatus {
    object Optimal : BmiStatus()

    object Neutral : BmiStatus()

    object Warning : BmiStatus()

    object Poor : BmiStatus()
}

fun BmiStatus.toMetricStatus(): MetricStatus =
    when (this) {
        BmiStatus.Optimal -> MetricStatus.OPTIMAL
        BmiStatus.Neutral -> MetricStatus.NEUTRAL
        BmiStatus.Warning -> MetricStatus.WARNING
        BmiStatus.Poor -> MetricStatus.POOR
    }
