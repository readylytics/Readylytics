package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/** Bundles the two coroutine dispatchers the Workouts tab needs, keeping the
 *  [WorkoutsViewModel] constructor within detekt's LongParameterList threshold. */
@Singleton
class WorkoutsDispatchers
    @Inject
    constructor(
        @IoDispatcher val io: CoroutineDispatcher,
        @DefaultDispatcher val default: CoroutineDispatcher,
    )
