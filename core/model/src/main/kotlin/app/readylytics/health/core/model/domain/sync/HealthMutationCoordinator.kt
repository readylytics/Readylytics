package app.readylytics.health.core.model.domain.sync

interface HealthMutationCoordinator {
    suspend fun <T> withMutation(block: suspend () -> T): T

    suspend fun <T> withMaintenance(operationId: String, block: suspend () -> T): T
}
