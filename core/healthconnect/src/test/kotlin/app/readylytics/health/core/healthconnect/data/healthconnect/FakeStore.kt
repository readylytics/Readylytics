package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.IntervalSourceRecord
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SessionSpans
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import java.time.LocalDate
import java.time.ZoneId

/**
 * Minimal counting fake for [HealthChangeIngestionStore]/[HealthIngestionStore]/[TransactionRunner]
 * used by the page-batching tests: it records how many times the batched write paths are invoked
 * rather than performing any real persistence, so a test can assert "one transaction, one batch
 * persist call" for a 1,000-record page (brief Step 1) without a Room instance.
 */
class FakeStore : HealthChangeIngestionStore, HealthIngestionStore, TransactionRunner {
    var transactionCount = 0
    var batchPersistCount = 0
    var affectedDateCalls = 0
    var deleteCalls = 0
    var pagesApplied = 0

    /**
     * When set, the transaction whose 1-based ordinal equals this value throws instead of
     * running -- simulating a failed page (e.g. a Room write failure) for the brief's "a failed
     * page leaves token 19" case. Thrown BEFORE [transactionCount]/[pagesApplied] increment, so
     * a failure on transaction N leaves both counters at N-1: the prior page's committed state,
     * never a partial/corrupted count for the failed one.
     */
    var failOnTransactionNumber: Int? = null

    override suspend fun <R> runInTransaction(block: suspend () -> R): R {
        if (transactionCount + 1 == failOnTransactionNumber) {
            error("Simulated transaction failure on page ${transactionCount + 1}")
        }
        transactionCount++
        pagesApplied++
        return block()
    }

    // --- HealthIngestionStore ---

    override suspend fun persist(batch: HealthIngestionBatch) {
        batchPersistCount++
    }

    override suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
        batchPersistCount++
    }

    override suspend fun replaceHrvSources(sources: List<SourcePayload<HrvInput>>) {
        batchPersistCount++
    }

    override suspend fun clearFrozenBaselines(start: LocalDate, endExclusive: LocalDate, zoneId: ZoneId) = Unit

    override suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int = 0

    override suspend fun countHrvInRange(startMs: Long, endMs: Long): Int = 0

    override suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int = 0

    override suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int = 0

    override suspend fun persistSingleWorkoutRoute(
        workoutId: String,
        routePoints: List<WorkoutRoutePoint>,
        routeState: String,
        totalDistanceMeters: Float?,
        avgSpeedKmh: Float?,
        elevationGainMeters: Float?,
    ) = Unit

    override suspend fun reconcileWindow(scan: CompleteTypeScan, zoneId: ZoneId): ScoreInvalidation.AffectedRange? =
        null

    // --- HealthChangeIngestionStore ---

    override suspend fun affectedDatesForRecords(
        type: HealthDataType,
        ids: List<String>,
        zoneId: ZoneId,
    ): Set<LocalDate> {
        if (ids.isEmpty()) return emptySet()
        affectedDateCalls++
        return emptySet()
    }

    override suspend fun deleteRecords(type: HealthDataType, ids: List<String>) {
        if (ids.isEmpty()) return
        deleteCalls++
    }

    override suspend fun sessionSpansOverlapping(startMs: Long, endMs: Long): SessionSpans =
        SessionSpans(emptyList(), emptyList())

    override suspend fun heartRateSamplesForMetrics(
        recordType: String,
        startMs: Long,
        endMs: Long,
    ): List<DomainHeartRateSample> = emptyList()

    override suspend fun persistPreparedWorkouts(prepared: List<PreparedWorkout>) {
        if (prepared.isEmpty()) return
        batchPersistCount++
    }

    override suspend fun workoutsOverlapping(startMs: Long, endMs: Long): List<WorkoutInput> = emptyList()

    override suspend fun getIntervalSource(sourceId: String): IntervalSourceRecord? = null

    override suspend fun persistIntervalEnrichment(
        preparedWorkouts: List<PreparedWorkout>,
        sourceUpserts: List<IntervalSourceRecord>,
        sourceDeletes: List<String>,
        dirtyDates: Set<LocalDate>,
    ) = Unit
}
