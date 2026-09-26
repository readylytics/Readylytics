package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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

        override suspend fun <T> withMutation(block: suspend () -> T): T {
            val context = currentCoroutineContext()
            val owner = context[MutationOwner]
            if (owner?.coordinator === this && owner.job === context[Job]) return block()
            return mutex.withLock {
                check(stateDao.getOrCreate().maintenanceOperationId == null) { "MAINTENANCE_PENDING" }
                val marker = MutationOwner(this)
                withContext(marker) {
                    // withContext installs its own Job; bind ownership inside that exact context.
                    marker.job = currentCoroutineContext()[Job]
                    block()
                }
            }
        }

        private class MutationOwner(
            val coordinator: HealthMutationCoordinatorImpl,
        ) : AbstractCoroutineContextElement(Key) {
            var job: Job? = null
            companion object Key : CoroutineContext.Key<MutationOwner>
        }

        override suspend fun <T> withMaintenance(
            operationId: String,
            block: suspend () -> T,
        ): T =
            mutex.withLock {
                val current = stateDao.getOrCreate()
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
