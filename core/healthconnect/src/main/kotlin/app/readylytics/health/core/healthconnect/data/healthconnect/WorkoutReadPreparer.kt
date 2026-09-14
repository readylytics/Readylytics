package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.core.model.domain.model.DomainIntervalTotal
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.util.SessionTotalsResolver
import app.readylytics.health.core.model.domain.util.logD
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs every Health Connect SDK read one EXERCISE upsertion needs -- route consent, plus the two
 * optional interval totals (distance, elevation) -- so `HealthChangeSynchronizerImpl` can resolve
 * them all BEFORE opening a Room writer transaction (H5/WP-09). These are binder round-trips
 * (`readRecords`, the lazy `exerciseRouteResult` accessor); holding a Room write transaction
 * across them was the bug this preparer exists to remove.
 *
 * Each read is captured as a [ReadOutcome] rather than degraded into a bare nullable value, so the
 * eventual commit can tell "the read succeeded and found nothing" ([ReadOutcome.Available] of an
 * empty list/null -- an authoritative removal) apart from "the read was denied"
 * ([ReadOutcome.Denied] -- preserve whatever is already stored). A transient failure propagates
 * (never silently becomes absence), matching the retry-on-throw contract the rest of this module
 * relies on.
 */
@Singleton
class WorkoutReadPreparer
    @Inject
    constructor(
        private val client: HealthConnectClient,
    ) {
        suspend fun prepare(record: ExerciseSessionRecord, baseWorkout: WorkoutInput): PreparedWorkout =
            PreparedWorkout(
                workout = baseWorkout,
                route = readRoute(record),
                distanceMeters = readIntervalTotal<DistanceRecord>(record) { it.toIntervalTotal() },
                elevationMeters = readIntervalTotal<ElevationGainedRecord>(record) { it.toIntervalTotal() },
            )

        /**
         * `ExerciseRouteResult.NoData` is a genuine, permitted "no route" observation --
         * [ReadOutcome.Available] of an empty list. Only `ConsentRequired` (or a caught
         * permission exception) is [ReadOutcome.Denied]: an empty list caused by a consent
         * failure must never look like an authoritative "no route" removal.
         */
        private fun readRoute(record: ExerciseSessionRecord): ReadOutcome<List<WorkoutRoutePoint>> =
            try {
                when (val result = record.exerciseRouteResult) {
                    is ExerciseRouteResult.Data ->
                        ReadOutcome.Available(
                            result.exerciseRoute.route
                                .map { location ->
                                    WorkoutRoutePoint(
                                        workoutId = record.metadata.id,
                                        latitude = location.latitude,
                                        longitude = location.longitude,
                                        altitude = location.altitude?.inMeters,
                                        timestampMs = location.time.toEpochMilli(),
                                        horizontalAccuracy = location.horizontalAccuracy?.inMeters?.toFloat(),
                                        verticalAccuracy = location.verticalAccuracy?.inMeters?.toFloat(),
                                    )
                                }
                                .sortedBy { it.timestampMs },
                        )
                    is ExerciseRouteResult.ConsentRequired -> ReadOutcome.Denied
                    else -> ReadOutcome.Available(emptyList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                logD("WorkoutReadPreparer") { "Permission not granted: ${e.message}" }
                ReadOutcome.Denied
            } catch (e: Exception) {
                if (e.asHealthConnectSecurityCause() != null) ReadOutcome.Denied else throw e
            }

        /**
         * Same-package attribution of one optional interval record type (distance, elevation) to
         * this session -- the per-session equivalent of the bulk `readIntervalTotals` +
         * [SessionTotalsResolver] pass in `IntervalTotalsReader`. Permission-denied is
         * [ReadOutcome.Denied]; a permitted read that resolves no matching total is
         * [ReadOutcome.Available] of null (a genuine, authoritative absence).
         */
        private suspend inline fun <reified T : Record> readIntervalTotal(
            record: ExerciseSessionRecord,
            noinline map: (T) -> DomainIntervalTotal,
        ): ReadOutcome<Float?> =
            try {
                val totals = mutableListOf<DomainIntervalTotal>()
                var pageToken: String? = null
                do {
                    val response =
                        client.readRecords(
                            ReadRecordsRequest(
                                recordType = T::class,
                                timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
                                pageToken = pageToken,
                            ),
                        )
                    totals += response.records.map(map)
                    pageToken = response.pageToken
                } while (pageToken != null)
                val resolved =
                    SessionTotalsResolver.totalFor(
                        sessionStart = record.startTime,
                        sessionEnd = record.endTime,
                        sessionOrigin = record.metadata.dataOrigin.packageName,
                        totals = totals,
                    )
                ReadOutcome.Available(resolved?.toFloat())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                logD("WorkoutReadPreparer") { "Permission not granted: ${e.message}" }
                ReadOutcome.Denied
            } catch (e: Exception) {
                if (e.asHealthConnectSecurityCause() != null) ReadOutcome.Denied else throw e
            }
    }
