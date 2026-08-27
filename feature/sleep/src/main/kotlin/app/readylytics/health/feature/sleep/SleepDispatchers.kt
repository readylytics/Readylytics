package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles the two coroutine dispatchers the Sleep tab needs, keeping the
 * [SleepViewModel] constructor within detekt's LongParameterList threshold.
 */
@Singleton
class SleepDispatchers
    @Inject
    constructor(
        @IoDispatcher val io: CoroutineDispatcher,
        @DefaultDispatcher val default: CoroutineDispatcher,
    )
