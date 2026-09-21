package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType

/**
 * Test-only convenience accessors for [InMemoryScanStagingStore], kept as extension functions in
 * their own file so the store's member-function count stays well under detekt's `TooManyFunctions`
 * threshold as [ScanStagingStore] grows new operations.
 */
fun InMemoryScanStagingStore.stagedIds(
    scan: ScanIdentity,
    type: HealthDataType,
): Set<String> = staged[Triple(scan.runId, scan.chunkId, type.name)]?.toSet() ?: emptySet()

fun InMemoryScanStagingStore.clearAll() {
    staged.clear()
    states.clear()
}
