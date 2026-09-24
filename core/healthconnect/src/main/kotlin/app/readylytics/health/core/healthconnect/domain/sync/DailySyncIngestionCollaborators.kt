package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collaborators participating in the Health Connect ingestion and reconciliation stages of
 * [DailySyncUseCase].
 */
@Singleton
data class DailySyncIngestionCollaborators
    @Inject
    constructor(
        val sessionLinkReconciler: SessionLinkReconciler,
        val changeSynchronizer: HealthChangeSynchronizer,
        val healthIngestionStore: HealthIngestionStore,
        val ingestionCoordinator: HealthIngestionCoordinator,
        val stepCountFetcher: StepCountFetcher,
    )
