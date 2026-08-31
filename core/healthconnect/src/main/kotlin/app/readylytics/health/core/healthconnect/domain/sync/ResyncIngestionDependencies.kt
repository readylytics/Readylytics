package app.readylytics.health.core.healthconnect.domain.sync

import javax.inject.Inject

/**
 * Bundles the Health Connect data-producer collaborators of [ResyncRangeUseCase] so its
 * constructor stays within the repository's LongParameterList budget. Both members read Health
 * Connect and feed the walk-forward: [ingestionCoordinator] streams the paged record ingest,
 * [stepCountFetcher] resolves the day-keyed step totals.
 */
class ResyncIngestionDependencies
    @Inject
    constructor(
        val ingestionCoordinator: HealthIngestionCoordinator,
        val stepCountFetcher: StepCountFetcher,
    )
