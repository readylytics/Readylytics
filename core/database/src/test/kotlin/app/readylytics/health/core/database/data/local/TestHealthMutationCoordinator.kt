package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator

internal object TestHealthMutationCoordinator : HealthMutationCoordinator {
    override suspend fun <T> withMutation(block: suspend () -> T): T = block()

    override suspend fun <T> withMaintenance(
        operationId: String,
        block: suspend () -> T,
    ): T = block()
}
