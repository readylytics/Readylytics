package app.readylytics.health.core.model.domain.model

object PermittedRecommendationMapper {
    fun resolve(status: MetricStatus, flags: List<RecoveryFlag>): PermittedRecommendation =
        when {
            flags.contains(RecoveryFlag.ILLNESS_ONSET) -> PermittedRecommendation.REST
            flags.contains(RecoveryFlag.OVERREACHING) -> PermittedRecommendation.ACTIVE_RECOVERY
            status == MetricStatus.POOR -> PermittedRecommendation.REST
            status == MetricStatus.WARNING -> PermittedRecommendation.ACTIVE_RECOVERY
            status == MetricStatus.NO_DATA -> PermittedRecommendation.UNKNOWN
            else -> PermittedRecommendation.TRAIN
        }
}
