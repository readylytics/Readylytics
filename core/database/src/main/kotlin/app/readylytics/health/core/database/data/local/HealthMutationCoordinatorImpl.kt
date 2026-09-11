package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthMutationCoordinatorImpl
    @Inject
    constructor(
        private val stateDao: HealthMutationStateDao,
    ) : HealthMutationCoordinator {
        private val mutex = Mutex()

        override suspend fun <T> withMutation(block: suspend () -> T): T =
            mutex.withLock {
                check(stateDao.current().maintenanceOperationId == null) { "MAINTENANCE_PENDING" }
                block()
            }

        override suspend fun <T> withMaintenance(
            operationId: String,
            block: suspend () -> T,
        ): T =
            mutex.withLock {
                val current = stateDao.current()
                val existingOp = current.maintenanceOperationId
                check(existingOp == null || existingOp == operationId) {
                    "MAINTENANCE_PENDING: $existingOp"
                }
                if (existingOp == null) {
                    stateDao.setMaintenance(operationId, "ACTIVE")
                }
                val result = block()
                stateDao.setMaintenance(null, null)
                result
            }
    }
