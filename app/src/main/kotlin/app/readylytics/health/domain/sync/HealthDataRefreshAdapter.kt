package app.readylytics.health.domain.sync

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthDataRefreshAdapter
    @Inject
    constructor(
        private val healthSyncUseCase: HealthSyncUseCase,
    ) : HealthDataRefresh {
        override suspend fun refreshAffectedWindow() {
            healthSyncUseCase.sync()
        }
    }
