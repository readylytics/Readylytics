package app.readylytics.health.domain.sync.link

interface SessionLinkReconciler {
    suspend fun reconcile(
        startMs: Long,
        endMs: Long,
        zoneThresholds: IntArray,
    )
}
