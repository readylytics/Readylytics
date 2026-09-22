package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import javax.inject.Inject

/**
 * Bundles the Health Connect data-producer collaborators of [ResyncRangeUseCase] so its
 * constructor stays within the repository's LongParameterList budget. Members read Health
 * Connect, stage scan identities, and feed the walk-forward: [ingestionCoordinator] streams
 * the paged record ingest, [stepCountFetcher] resolves the day-keyed step totals, and
 * [staging] tracks seen record identities.
 *
 * [staging] is deliberately required, with no default: a heap-backed store would silently
 * reintroduce PERF-001 and stop `StagedDeletionReconciler` from ever seeing a scan reach
 * `COMPLETE`, so losing the `RoomScanStagingStore` Hilt binding must fail wiring, not degrade.
 */
class ResyncIngestionDependencies
    @Inject
    constructor(
        val ingestionCoordinator: HealthIngestionCoordinator,
        val stepCountFetcher: StepCountFetcher,
        val staging: ScanStagingStore,
    )
