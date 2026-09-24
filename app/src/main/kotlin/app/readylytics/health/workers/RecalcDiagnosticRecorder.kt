package app.readylytics.health.workers

import app.readylytics.health.BuildConfig
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.crashreport.RecalcDiagnostic
import app.readylytics.health.core.model.domain.crashreport.RecalcDiagnosticStore
import app.readylytics.health.core.model.domain.crashreport.formatRecalcDiagnostic
import app.readylytics.health.core.model.domain.crashreport.shouldRecordRecalcDiagnostic
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

/**
 * Records a [RecalcDiagnostic] when an unexpected trigger (see [RecalcTrigger.isUnexpected])
 * resolves to more than the normal few-day window, so the next app start can offer it for sharing
 * through the crash-report dialog. Best effort: a failure here never affects the recompute itself.
 */
class RecalcDiagnosticRecorder
    @Inject
    constructor(
        private val store: RecalcDiagnosticStore,
        private val dirtyRangeStore: DirtyRangeStore,
        private val clock: Clock,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /** [resolveRange] is only invoked for unexpected triggers. */
        suspend fun recordIfLarge(
            trigger: RecalcTrigger,
            triggerDetail: String?,
            recomputeOnly: Boolean,
            resolveRange: suspend () -> ScoreInvalidation.AffectedRange?,
        ) {
            if (!trigger.isUnexpected) return
            try {
                val range = resolveRange()
                if (range != null && shouldRecordRecalcDiagnostic(trigger, range.start, range.endInclusive)) {
                    record(trigger, triggerDetail, recomputeOnly, range)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Failed to record recalculation diagnostic" }
            }
        }

        private suspend fun record(
            trigger: RecalcTrigger,
            triggerDetail: String?,
            recomputeOnly: Boolean,
            range: ScoreInvalidation.AffectedRange,
        ) {
            val entry =
                formatRecalcDiagnostic(
                    RecalcDiagnostic(
                        timestampIso = clock.instant().toString(),
                        appVersionName = BuildConfig.VERSION_NAME,
                        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        trigger = trigger,
                        triggerDetail = triggerDetail,
                        recomputeOnly = recomputeOnly,
                        startDate = range.start,
                        endDate = range.endInclusive,
                        pendingTickets = dirtyRangeStore.pending(PENDING_TICKET_LIMIT),
                    ),
                )
            logI(TAG) { entry }
            withContext(ioDispatcher) { store.append(entry) }
        }

        private companion object {
            const val TAG = "RecalcDiagnosticRecorder"
            const val PENDING_TICKET_LIMIT = 100
        }
    }
