package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.InMemoryScanStagingStore
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import javax.inject.Inject

/**
 * Bundles the Health Connect data-producer collaborators of [ResyncRangeUseCase] so its
 * constructor stays within the repository's LongParameterList budget. Members read Health
 * Connect, stage scan identities, and feed the walk-forward: [ingestionCoordinator] streams
 * the paged record ingest, [stepCountFetcher] resolves the day-keyed step totals, and
 * [staging] tracks seen record identities.
 */
class ResyncIngestionDependencies
    @Inject
    constructor(
        val ingestionCoordinator: HealthIngestionCoordinator,
        val stepCountFetcher: StepCountFetcher,
        val staging: ScanStagingStore = InMemoryScanStagingStore(),
    )
