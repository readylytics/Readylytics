package app.readylytics.health.domain.scoring.components

data class CircadianConsistencyConfig(
    val thresholdMinutes: Int,
    val useShiftWorkerMode: Boolean = false,
    val evaluationDays: Int = 14,
    val baselineDays: Int = 7,
) {
    init {
        require(thresholdMinutes >= 0) { "Threshold must be non-negative" }
        require(evaluationDays > 0) { "Evaluation days must be positive" }
        require(baselineDays > 0) { "Baseline days must be positive" }
    }

    val isDisabled: Boolean
        get() = thresholdMinutes == Int.MAX_VALUE
}
