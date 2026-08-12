package app.readylytics.health.domain.model

object PermittedRecommendationMapper {
    fun resolve(status: MetricStatus, flags: List<RecoveryFlag>): PermittedRecommendation {
        if (flags.contains(RecoveryFlag.ILLNESS_ONSET)) {
            return PermittedRecommendation.REST
        }
        if (flags.contains(RecoveryFlag.OVERREACHING)) {
            return PermittedRecommendation.ACTIVE_RECOVERY
        }

        return when (status) {
            MetricStatus.POOR -> PermittedRecommendation.REST
            MetricStatus.WARNING -> PermittedRecommendation.ACTIVE_RECOVERY
            MetricStatus.NEUTRAL -> PermittedRecommendation.TRAIN
            MetricStatus.OPTIMAL -> PermittedRecommendation.TRAIN
            MetricStatus.CALIBRATING -> PermittedRecommendation.TRAIN
            MetricStatus.NO_DATA -> PermittedRecommendation.UNKNOWN
        }
    }
}
