package app.readylytics.health.data.backup

import app.readylytics.health.core.model.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Clock
import javax.inject.Inject

/**
 * Execution environment for [BackupRotationService]: the IO dispatcher it runs on and the [Clock]
 * that stamps rotation journals, staged-archive names and audit events. Grouped so the service's
 * constructor stays within detekt's `LongParameterList` threshold.
 */
class BackupRuntime
    @Inject
    constructor(
        @param:IoDispatcher val ioDispatcher: CoroutineDispatcher,
        val clock: Clock,
    )
