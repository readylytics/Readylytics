package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.util.logD
import javax.inject.Inject

/**
 * Dense phase of one window's ingestion: streams heart rate, HRV, and steps page-by-page (HC-001),
 * in that order, sharing one page-progress counter across all three. HR/HRV pages are tagged
 * against the already-persisted [IngestionSessionContext] by [HeartSampleStreamer]; steps carries no
 * session relationship and is staged + persisted directly.
 */
internal class DenseWindowStreamPhase
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val healthIngestionStore: HealthIngestionStore,
        private val staging: ScanStagingStore,
    ) {
        private val streamer: HeartSampleStreamer = HeartSampleStreamer(hcRepo, healthIngestionStore, staging)

        suspend fun streamAll(
            params: IngestWindowParams,
            sessionContext: IngestionSessionContext,
        ) {
            var pagesIngested = 0
            val deviceByType = params.prefs.deviceByDataType
            fun deviceFor(type: HealthDataType): String? = deviceByType[type.name]?.takeIf { it.isNotBlank() }

            streamer.streamHeartRate(
                params = params,
                sessionContext = sessionContext,
                device = deviceFor(HealthDataType.HEART_RATE),
            ) {
                pagesIngested++
                params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
            }

            streamer.streamHrv(
                params = params,
                sessionContext = sessionContext,
                device = deviceFor(HealthDataType.HRV),
            ) {
                pagesIngested++
                params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
            }

            streamSteps(params, device = deviceFor(HealthDataType.STEPS)) {
                pagesIngested++
                params.onProgress?.invoke(ResyncPhase.INGEST, pagesIngested, 0)
            }
        }

        /**
         * Steps can be as dense as HR/HRV (a continuously-recorded day reaches tens of thousands of
         * rows), so it is streamed page-by-page the same way (HC-001) rather than bulk-fetched with
         * the low-cardinality vitals in [BulkWindowIngestPhase]. Unlike HR/HRV, steps rows carry no
         * session relationship, so each page is staged (raw, unfiltered ids -- WP-18) and persisted
         * (device-filtered) directly, with no mapper/session lookup involved. The scan is only ever
         * marked COMPLETE once the whole paged read succeeds (HC-005): a denied or failed page leaves
         * it INCOMPLETE, so [HealthIngestionStore.reconcileWindow] never deletes rows against a
         * partial id set.
         */
        private suspend fun streamSteps(
            params: IngestWindowParams,
            device: String?,
            onPageDone: () -> Unit,
        ): ReadOutcome<Unit> {
            staging.beginTypeScan(params.scanIdentity, HealthDataType.STEPS, resume = false)
            var persisted = 0
            val outcome =
                hcRepo.readStepsRecordsPaged(
                    from = params.windowStart,
                    to = params.windowEnd,
                    retryScope = params.retryBudget,
                ) { page ->
                    staging.stageIds(params.scanIdentity, HealthDataType.STEPS, page.map { it.id })
                    val filtered = DeviceSourceFilter.filterToDevice(page, device) { it.deviceName }
                    healthIngestionStore.persist(stepsOnlyBatch(filtered))
                    persisted += filtered.size
                    onPageDone()
                }
            if (outcome is ReadOutcome.Available) {
                staging.markTypeScanComplete(params.scanIdentity, HealthDataType.STEPS)
            }
            logD(TELEMETRY_TAG) { "[INGESTION] steps records persisted: $persisted" }
            return outcome
        }

        /**
         * Minimal [HealthIngestionBatch] carrying only one page of steps rows -- used by [streamSteps]
         * so each page commits independently instead of waiting for the whole window's steps to be
         * read (HC-001).
         */
        private fun stepsOnlyBatch(records: List<DomainStepsRecord>): HealthIngestionBatch =
            HealthIngestionBatch(
                sleepSessions = emptyList(),
                sleepStages = emptyList(),
                workouts = emptyList(),
                weights = emptyList(),
                bodyFatSamples = emptyList(),
                bloodPressureSamples = emptyList(),
                oxygenSaturationSamples = emptyList(),
                bodyTemperatureSamples = emptyList(),
                stepRecords =
                    records.map { record ->
                        StepRecordInput(
                            id = record.id,
                            startTime = record.startTime.toEpochMilli(),
                            endTime = record.endTime.toEpochMilli(),
                            count = record.count,
                            deviceName = record.deviceName,
                        )
                    },
            )

        private companion object {
            const val TELEMETRY_TAG = "ResyncTelemetry"
        }
    }
