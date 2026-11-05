package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.di.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Clock
import javax.inject.Inject

/**
 * Execution environment for [ScoringRepositoryImpl]: the dispatcher scoring work runs on and the
 * [Clock] that anchors single-day runs. Grouped so the repository's constructor stays within
 * detekt's `LongParameterList` threshold without a dead `Clock` default.
 */
class ScoringRuntime
    @Inject
    constructor(
        @param:DefaultDispatcher val defaultDispatcher: CoroutineDispatcher,
        val clock: Clock,
    )
