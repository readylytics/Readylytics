package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Clock
import javax.inject.Inject

/**
 * Execution environment for [ResyncRangeUseCase]: the IO dispatcher the resync runs on and the
 * [Clock] used for checkpoint and sync timestamps. Grouped so the use case's constructor stays
 * within detekt's `LongParameterList` threshold without a dead `Clock` default.
 */
class ResyncRuntime
    @Inject
    constructor(
        @param:IoDispatcher val ioDispatcher: CoroutineDispatcher,
        val clock: Clock,
    )
