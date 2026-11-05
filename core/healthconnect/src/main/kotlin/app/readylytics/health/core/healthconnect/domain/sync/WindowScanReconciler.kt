package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.TypeScanState
import app.readylytics.health.core.model.domain.util.logD
import javax.inject.Inject

/**
 * Final phase of one ingestion window: stages the id sets of every bulk-read record type, collects
 * the [CompleteTypeScan]s (bulk-staged plus already-settled paged ones), and reconciles deletions
 * against them via [HealthIngestionStore.reconcileWindow].
 */
internal class WindowScanReconciler
    @Inject
    constructor(
        private val healthIngestionStore: HealthIngestionStore,
        private val staging: ScanStagingStore,
    ) {
        suspend fun reconcileDeletions(
            params: IngestWindowParams,
            scans: List<CompleteTypeScan>,
        ): ScoreInvalidation.AffectedRange? {
            val zoneId = params.prefs.scoringZone()

            val results =
                scans.associate { scan ->
                    scan.type to healthIngestionStore.reconcileWindow(scan, zoneId)
                }

            logD(TELEMETRY_TAG) {
                val sleepMod = results[HealthDataType.SLEEP] != null
                val workMod = results[HealthDataType.EXERCISE] != null
                val hrMod = results[HealthDataType.HEART_RATE] != null || results[HealthDataType.HRV] != null
                "[INGESTION] reconciled deletes in chunk: " +
                    "sleep=${if (sleepMod) "yes" else "0"} " +
                    "workout=${if (workMod) "yes" else "0"} " +
                    "hr_sources=${if (hrMod) "yes" else "0"}"
            }

            return ScoreInvalidation.merge(results.values)
        }

        suspend fun stageBulkScans(
            params: IngestWindowParams,
            raw: RawBulkRecords,
        ): List<CompleteTypeScan> {
            val startMs = params.windowStart.toEpochMilli()
            val endExclusiveMs = params.windowEnd.toEpochMilli()
            fun deviceFor(type: HealthDataType) = params.prefs.deviceByDataType[type.name].orEmpty()

            suspend fun MutableList<CompleteTypeScan>.stage(
                outcome: ReadOutcome<List<String>>,
                type: HealthDataType,
            ) {
                staging.beginTypeScan(params.scanIdentity, type, resume = false)
                if (outcome !is ReadOutcome.Available) return
                staging.stageIds(params.scanIdentity, type, outcome.data)
                staging.markTypeScanComplete(params.scanIdentity, type)
                add(
                    CompleteTypeScan(
                        type = type,
                        windowStartMs = startMs,
                        windowEndExclusiveMs = endExclusiveMs,
                        sourceSelectionId = deviceFor(type),
                        scan = params.scanIdentity,
                    ),
                )
            }

            return buildList {
                stage(raw.sleepSessions.toIds { it.id }, HealthDataType.SLEEP)
                stage(raw.exerciseRecords.toIds { it.id }, HealthDataType.EXERCISE)
                // DB-001: vitals rows are keyed "<hcId>_<timestampMs>", so the staged identity is
                // that persisted row id -- no substringBefore('_') parsing anywhere, and a record
                // whose timestamp moved converges instead of leaving a stale duplicate behind.
                stage(raw.weightRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.WEIGHT)
                stage(raw.bodyFatRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.BODY_FAT)
                stage(
                    raw.bloodPressureRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" },
                    HealthDataType.BLOOD_PRESSURE,
                )
                stage(raw.spo2Records.toIds { "${it.id}_${it.time.toEpochMilli()}" }, HealthDataType.OXYGEN_SATURATION)
                stage(
                    raw.bodyTemperatureRecords.toIds { "${it.id}_${it.time.toEpochMilli()}" },
                    HealthDataType.BODY_TEMPERATURE,
                )
                stage(raw.vo2MaxRecords.toIds { it.id }, HealthDataType.VO2_MAX)
                addPagedScans(params, startMs, endExclusiveMs, ::deviceFor)
            }
        }

        /**
         * HEART_RATE, HRV, and STEPS are streamed/staged page-by-page (not bulk-staged by [stageBulkScans])
         * via [DenseWindowStreamPhase.streamAll], so their scan only reaches [TypeScanState.COMPLETE]
         * once the whole paged read has succeeded -- this just reads that already-settled state.
         */
        private suspend fun MutableList<CompleteTypeScan>.addPagedScans(
            params: IngestWindowParams,
            startMs: Long,
            endExclusiveMs: Long,
            deviceFor: (HealthDataType) -> String,
        ) {
            for (type in PAGED_DENSE_TYPES) {
                if (staging.stateOf(params.scanIdentity, type) == TypeScanState.COMPLETE) {
                    add(
                        CompleteTypeScan(
                            type = type,
                            windowStartMs = startMs,
                            windowEndExclusiveMs = endExclusiveMs,
                            sourceSelectionId = deviceFor(type),
                            scan = params.scanIdentity,
                        ),
                    )
                }
            }
        }

        private companion object {
            const val TELEMETRY_TAG = "ResyncTelemetry"

            /** Record types streamed/staged page-by-page rather than bulk-staged (HC-001). */
            val PAGED_DENSE_TYPES =
                listOf(HealthDataType.HEART_RATE, HealthDataType.HRV, HealthDataType.STEPS)
        }
    }

private inline fun <T> ReadOutcome<List<T>>.toIds(crossinline idSelector: (T) -> String): ReadOutcome<List<String>> =
    when (this) {
        is ReadOutcome.Available -> ReadOutcome.Available(data.map { idSelector(it) })
        ReadOutcome.Denied -> ReadOutcome.Denied
        ReadOutcome.Unsupported -> ReadOutcome.Unsupported
    }
