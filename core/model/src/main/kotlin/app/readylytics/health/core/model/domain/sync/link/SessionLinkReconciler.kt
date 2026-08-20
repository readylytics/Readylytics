package app.readylytics.health.core.model.domain.sync.link

interface SessionLinkReconciler {
    suspend fun reconcile(
        startMs: Long,
        endMs: Long,
        zoneThresholds: IntArray,
    )
}
