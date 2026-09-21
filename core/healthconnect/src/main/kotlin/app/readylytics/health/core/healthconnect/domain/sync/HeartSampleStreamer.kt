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
        staging.beginTypeScan(params.scanIdentity, HealthDataType.HEART_RATE, params.resumeStagedScan)
        var sampleCount = 0
        val outcome =
            hcRepo.readHeartRateSamplesPaged(
                from = params.windowStart,
                to = params.windowEnd,
                startPageToken = params.hrStartPageToken,
            ) { page, nextToken ->
                // Staged before the write: a crash between staging and persisting re-reads the page
                // and re-stages the same ids idempotently, whereas the reverse order could mark a
                // persisted id unseen and delete it on the next reconcile.
                staging.stageIds(params.scanIdentity, HealthDataType.HEART_RATE, page.map { it.id })
                sampleCount += persistHeartRatePage(page, sessionContext, device)
                onPageDone()
                params.onTokenUpdated?.invoke(nextToken, null)
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
        staging.beginTypeScan(params.scanIdentity, HealthDataType.HRV, params.resumeStagedScan)
        var sampleCount = 0
        val outcome =
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
        val hrSources =
            HeartRateMapper.mapToInputs(
                page,
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
        return filteredHr.sumOf { it.rows.size }
    }

    private suspend fun persistHrvPage(
        page: List<DomainHrvRecord>,
        sessionContext: IngestionSessionContext,
        device: String?,
    ): Int {
        val hrvSources =
            HrvMapper.mapToInputs(
                page,
                sessionContext.sleepInputs,
            )
        val filteredHrv =
            hrvSources.map { source ->
                source.copy(
                    rows = DeviceSourceFilter.filterToDevice(source.rows, device) { it.deviceName },
                )
            }
        healthIngestionStore.replaceHrvSources(filteredHrv)
        return filteredHrv.sumOf { it.rows.size }
    }
}
