package app.readylytics.health.domain.model

sealed class BloodPressureStatus {
    object Optimal : BloodPressureStatus()

    object Neutral : BloodPressureStatus()

    object HypertensionStage1 : BloodPressureStatus()

    object HypertensionStage2 : BloodPressureStatus()

    val displayName: String
        get() =
            when (this) {
                Optimal -> "Optimal"
                Neutral -> "Elevated"
                HypertensionStage1 -> "Hypertension Stage 1"
                HypertensionStage2 -> "Hypertension Stage 2+"
            }
}

fun BloodPressureStatus.toMetricStatus(): MetricStatus =
    when (this) {
        BloodPressureStatus.Optimal -> MetricStatus.OPTIMAL
        BloodPressureStatus.Neutral -> MetricStatus.NEUTRAL
        BloodPressureStatus.HypertensionStage1 -> MetricStatus.WARNING
        BloodPressureStatus.HypertensionStage2 -> MetricStatus.POOR
    }
