package app.readylytics.health.core.model.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary

/**
 * Tri-state result of assembling one day's [DailySummary].
 *
 * Only [Computed] and [Absent] are genuinely complete candidates -- the ONLY two variants a
 * publisher (see `DirtySummaryPublisher`) may ever write. [Unavailable] means assembly could not
 * complete this pass (a transient failure at any assembler boundary, or cancellation): the caller
 * must leave the previous complete day, its canonical workout values, and its dirty ticket
 * entirely alone -- no partial merge, no "new load with old readiness."
 */
sealed interface DayAssembly {
    /**
     * The day's required source input (its sleep session) was present. A full derived candidate,
     * safe to publish and to seed the next day's walk-forward state.
     */
    data class Computed(val summary: DailySummary) : DayAssembly

    /**
     * The day's required source input (its sleep session) was confirmed absent in Room at the
     * captured authoritative generation -- e.g. its only sleep session was deleted. Distinct from
     * [Unavailable]: this is still a genuinely complete, publishable candidate. Independent inputs
     * (steps, vitals, load) are computed normally; only the sleep-dependent fields are explicitly
     * nulled and flagged as no-data rather than silently carried over from a prior generation. A
     * denied Health Connect read must never produce this -- only a confirmed absence of the
     * required row(s) in local Room storage does.
     */
    data class Absent(val summary: DailySummary) : DayAssembly

    /**
     * Assembly could not complete this pass. [reason] is a fixed, internal-only safe reason code
     * (see [DayAssemblyUnavailableReason]) -- never raw exception text, which must not leak into a
     * persisted field or a user-facing string.
     */
    data class Unavailable(val reason: String) : DayAssembly
}

/** Fixed, internal-only reason codes for [DayAssembly.Unavailable]. */
object DayAssemblyUnavailableReason {
    const val BASE_ASSEMBLY_FAILED = "BASE_ASSEMBLY_FAILED"
    const val READINESS_ASSEMBLY_FAILED = "READINESS_ASSEMBLY_FAILED"
    const val FINAL_ASSEMBLY_FAILED = "FINAL_ASSEMBLY_FAILED"
    const val RECOMMENDATION_ASSEMBLY_FAILED = "RECOMMENDATION_ASSEMBLY_FAILED"
}

/**
 * The candidate [DailySummary] for [DayAssembly.Computed]/[DayAssembly.Absent]; `null` for
 * [DayAssembly.Unavailable].
 */
fun DayAssembly.summaryOrNull(): DailySummary? =
    when (this) {
        is DayAssembly.Computed -> summary
        is DayAssembly.Absent -> summary
        is DayAssembly.Unavailable -> null
    }
