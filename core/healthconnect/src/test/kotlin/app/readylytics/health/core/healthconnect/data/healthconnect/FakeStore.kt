package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.*

class FakeStore : HealthChangeIngestionStore, HealthIngestionStore, TransactionRunner {
        var transactionCount = 0
        var batchPersistCount = 0
        var affectedDateCalls = 0
        var deleteCalls = 0
        var pagesApplied = 0

        override suspend fun <R> runInTransaction(block: suspend () -> R): R {
            transactionCount++
            pagesApplied++
            return block()
        }

        override suspend fun persist(batch: app.readylytics.health.core.model.domain.sync.IngestionBatch) {
            batchPersistCount++
        }
        override suspend fun clearFrozenBaselines(affectedDates: Set<java.time.LocalDate>) { Unit }
        override suspend fun replaceHeartRateSources(
            sources: List<app.readylytics.health.core.model.domain.sync.HeartRateSourceInput>
        ) { Unit }
        override suspend fun replaceHrvSources(
            sources: List<app.readylytics.health.core.model.domain.sync.HrvSourceInput>
        ) { Unit }

        override suspend fun affectedDatesForRecord(
            type: HealthDataType, hcRecordId: String, zoneId: java.time.ZoneId
        ): Set<java.time.LocalDate> {
            affectedDateCalls++
            return emptySet()
        }
        override suspend fun deleteRecord(type: HealthDataType, hcRecordId: String) {
            deleteCalls++
        }
        override suspend fun affectedDatesForRecords(
            type: HealthDataType, ids: List<String>, zoneId: java.time.ZoneId
        ): Set<java.time.LocalDate> {
            affectedDateCalls++
            return emptySet()
        }
        override suspend fun deleteRecords(type: HealthDataType, ids: List<String>) {
            deleteCalls++
        }
        override suspend fun sessionSpansOverlapping(startMs: Long, endMs: Long): SessionSpans =
            SessionSpans(emptyList(), emptyList())
        override suspend fun heartRateSamplesForMetrics(
            recordType: String, startMs: Long, endMs: Long
        ): List<app.readylytics.health.core.model.domain.model.DomainHeartRateSample> = emptyList()
        override suspend fun persistPreparedWorkouts(
            prepared: List<app.readylytics.health.core.model.domain.sync.PreparedWorkout>
        ) {
            batchPersistCount++
        }
        override suspend fun workoutsOverlapping(
            startMs: Long, endMs: Long
        ): List<app.readylytics.health.core.model.domain.sync.WorkoutInput> = emptyList()
        override suspend fun getIntervalSource(
            sourceId: String
        ): app.readylytics.health.core.model.domain.sync.IntervalSourceRecord? = null
        override suspend fun persistIntervalEnrichment(
            preparedWorkouts: List<app.readylytics.health.core.model.domain.sync.PreparedWorkout>,
            sourceUpserts: List<app.readylytics.health.core.model.domain.sync.IntervalSourceRecord>,
            sourceDeletes: List<String>,
            dirtyDates: Set<java.time.LocalDate>,
        ) { Unit }
    }
