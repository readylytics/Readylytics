package app.readylytics.health.core.model.domain.model

/** Classifies daily steps against a user goal using the shared dashboard ladder. */
object StepsStatusClassifier {
    fun classify(
        stepCount: Int?,
        stepGoal: Int,
    ): MetricStatus {
        if (stepCount == null || stepGoal <= 0) return MetricStatus.CALIBRATING

        return when {
            stepCount >= stepGoal -> MetricStatus.OPTIMAL
            stepCount * 4L >= stepGoal * 3L -> MetricStatus.NEUTRAL
            stepCount * 2L >= stepGoal -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }
    }
}

/** Classifies heart-rate availability for the dashboard status treatment. */
object HeartRateStatusClassifier {
    fun classify(averageBpm: Int?): MetricStatus =
        if (averageBpm == null) MetricStatus.CALIBRATING else MetricStatus.NEUTRAL
}
