package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.valueOrPrevious

/**
 * An EXERCISE upsertion whose Health Connect SDK reads (route consent, distance and elevation
 * interval totals) are already resolved -- built by `WorkoutReadPreparer` OUTSIDE any Room writer
 * transaction (H5/WP-09), so [HealthIngestionStore.persistPreparedWorkouts] never holds the write
 * lock across a binder round-trip. [route]/[distanceMeters]/[elevationMeters] each carry a
 * [ReadOutcome] rather than a bare nullable value so the commit can tell "the read succeeded and
 * found nothing" ([ReadOutcome.Available] of an empty list/null -- an authoritative removal) apart
 * from "the read was denied or unsupported" ([ReadOutcome.Denied]/[ReadOutcome.Unsupported] --
 * preserve whatever is already stored).
 *
 * [workout] carries every other resolved field (id/times/duration/exerciseType/zones/trimp/avgHr/
 * device); its own route/distance/elevation/avgSpeed fields are unresolved placeholders,
 * superseded by the merge against the already-stored row.
 */
data class PreparedWorkout(
    val workout: WorkoutInput,
    val route: ReadOutcome<List<WorkoutRoutePoint>>,
    val distanceMeters: ReadOutcome<Float?>,
    val elevationMeters: ReadOutcome<Float?>,
)

/**
 * Available replaces -- including with an authoritatively empty/null value; Denied/Unsupported
 * preserves whatever [old] already was. The one merge rule every [PreparedWorkout] field (and any
 * future correction batch, e.g. H4's distance/elevation interval corrections) shares, so it is
 * never re-implemented per field or per caller.
 */
fun <T> mergeEnrichment(old: T, read: ReadOutcome<T>): T = read.valueOrPrevious(old)
