package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import app.readylytics.health.core.model.domain.sync.mappers.HrvMapper
import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal class HeartSampleStreamer(
    private val hcRepo: HealthConnectRepository,
    private val healthIngestionStore: HealthIngestionStore,
    private val staging: ScanStagingStore,
) {
    suspend fun streamHeartRate(
        params: IngestWindowParams,
        sessionContext: IngestionSessionContext,
        device: String?,
        onPageDone: () -> Unit,
    ): ReadOutcome<Unit> {
        staging.beginTypeScan(params.scanIdentity, HealthDataType.HEART_RATE, params.resumeHrScan)
        var sampleCount = 0
        val outcome =
            params.retryBudget.execute("hrPages") {
                hcRepo.readHeartRateSamplesPaged(
                    from = params.windowStart,
                    to = params.windowEnd,
                    startPageToken = params.hrStartPageToken,
                ) { page, nextToken ->
                    // Staged before the write: a crash between staging and persisting re-reads the
                    // page and re-stages the same ids idempotently, whereas the reverse order could
                    // mark a persisted id unseen and delete it on the next reconcile.
                    staging.stageIds(params.scanIdentity, HealthDataType.HEART_RATE, page.map { it.id })
                    sampleCount += persistHeartRatePage(page, sessionContext, device)
                    onPageDone()
                    params.onTokenUpdated?.invoke(nextToken, null)
                }
            }
        if (outcome is ReadOutcome.Available) {
            staging.markTypeScanComplete(params.scanIdentity, HealthDataType.HEART_RATE)
        }
        logD("HealthSync.Ingest") { "HR samples persisted: $sampleCount" }
        return outcome
    }

    suspend fun streamHrv(
        params: IngestWindowParams,
        sessionContext: IngestionSessionContext,
        device: String?,
        onPageDone: () -> Unit,
    ): ReadOutcome<Unit> {
        staging.beginTypeScan(params.scanIdentity, HealthDataType.HRV, params.resumeHrvScan)
        var sampleCount = 0
        val outcome =
            params.retryBudget.execute("hrvPages") {
                hcRepo.readHrvSamplesPaged(
                    from = params.windowStart,
                    to = params.windowEnd,
                    startPageToken = params.hrvStartPageToken,
                ) { page, nextToken ->
                    staging.stageIds(params.scanIdentity, HealthDataType.HRV, page.map { it.id })
                    sampleCount += persistHrvPage(page, sessionContext, device)
                    onPageDone()
                    params.onTokenUpdated?.invoke(null, nextToken)
                }
            }
        if (outcome is ReadOutcome.Available) {
            staging.markTypeScanComplete(params.scanIdentity, HealthDataType.HRV)
        }
        logD("HealthSync.Ingest") { "HRV samples persisted: $sampleCount" }
        return outcome
    }

    private suspend fun persistHeartRatePage(
        page: List<DomainHeartRateRecord>,
        sessionContext: IngestionSessionContext,
        device: String?,
    ): Int {
        var persisted = 0
        // PERF-001: a Health Connect page bounds parent cardinality, not nested sample count. The
        // transform buffer is therefore capped on samples: each slice's mapped payloads, the
        // distinct-timestamp link table HeartRateMapper builds, and the Room write it feeds all stay
        // proportional to TRANSFORM_SAMPLE_BUDGET instead of to the page's density. A single parent
        // whose payload already exceeds the budget forms its own slice -- the SDK owns that record
        // and there is no smaller unit to read.
        page.sliceBySampleBudget(TRANSFORM_SAMPLE_BUDGET, sampleCountOf = { it.samples.size }) { slice ->
            val hrSources =
                HeartRateMapper.mapToInputs(
                    slice,
                    sessionContext.sleepInputs,
                    sessionContext.workoutInputs,
                )
            val filteredHr =
                hrSources.map { source ->
                    source.copy(
                        rows = DeviceSourceFilter.filterToDevice(source.rows, device) { it.deviceName },
                    )
                }
            healthIngestionStore.replaceHeartRateSources(filteredHr)
            persisted += filteredHr.sumOf { it.rows.size }
        }
        return persisted
    }

    private suspend fun persistHrvPage(
        page: List<DomainHrvRecord>,
        sessionContext: IngestionSessionContext,
        device: String?,
    ): Int {
        var persisted = 0
        // HRV records carry exactly one RMSSD value each, so slicing by parent count is equivalent
        // to slicing by sample count here.
        page.sliceBySampleBudget(TRANSFORM_SAMPLE_BUDGET, sampleCountOf = { 1 }) { slice ->
            val hrvSources =
                HrvMapper.mapToInputs(
                    slice,
                    sessionContext.sleepInputs,
                )
            val filteredHrv =
                hrvSources.map { source ->
                    source.copy(
                        rows = DeviceSourceFilter.filterToDevice(source.rows, device) { it.deviceName },
                    )
                }
            healthIngestionStore.replaceHrvSources(filteredHrv)
            persisted += filteredHrv.sumOf { it.rows.size }
        }
        return persisted
    }
}

/**
 * Splits this list into contiguous sub-lists whose total [sampleCountOf] stays at or under
 * [budget], invoking [action] once per sub-list. A single element whose own sample count already
 * exceeds the budget still forms its own (oversized) slice -- there is no smaller unit to split it
 * into. Cooperative: checks cancellation before each slice and yields after each slice's work, so a
 * long paged transform never starves other coroutines or swallows cancellation.
 */
internal suspend fun <T> List<T>.sliceBySampleBudget(
    budget: Int,
    sampleCountOf: (T) -> Int,
    action: suspend (List<T>) -> Unit,
) {
    require(budget > 0) { "budget must be positive" }
    var start = 0
    while (start < size) {
        currentCoroutineContext().ensureActive()
        var end = start
        var samples = 0
        while (end < size && (end == start || samples + sampleCountOf(this[end]) <= budget)) {
            samples += sampleCountOf(this[end])
            end++
        }
        action(subList(start, end))
        start = end
        yield()
    }
}
