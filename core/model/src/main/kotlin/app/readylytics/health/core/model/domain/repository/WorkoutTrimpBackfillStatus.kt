package app.readylytics.health.core.model.domain.repository

/**
 * Reports whether any retained workout still lacks a canonical `modelTrimp` backfill.
 *
 * `modelTrimp` is written lazily: only a walk-forward recompute that touches a workout persists it.
 * A workout restored from a pre-SCORE-001 backup, or one whose day failed a recompute, therefore
 * keeps `modelTrimp = NULL` indefinitely. Such a row still contributes to ATL/CTL through
 * `COALESCE(modelTrimp, trimp)` but contributes nothing to Residual Fatigue, which is
 * canonical-only. Startup uses this port to detect that state and enqueue the existing
 * recompute-only resync, which converges: a recompute evaluates every workout it touches and
 * persists its `modelTrimpQuality` (a value, including `0f`, or `NULL` with quality `UNAVAILABLE`
 * when the workout has no HR samples), so the never-evaluated count reaches zero and the gate stops
 * firing. `UNAVAILABLE` rows are terminal -- no recompute can fill them -- and are deliberately not
 * counted; otherwise every HR-less workout would re-enqueue a full retained-history recompute on
 * every cold start.
 *
 * Convergence only holds because the residual-fatigue seed gate never looks further back than this
 * one does. See [app.readylytics.health.core.model.domain.scoring.FatigueHorizon.gateStartMs]: a
 * gate reaching past [hasUnbackfilledWorkouts]'s retention bound would block on rows this self-heal
 * deliberately cannot repair, pinning residual fatigue to null forever.
 *
 * Implemented in `core:database` over the workout DAO; kept here so `app` never reaches into a DAO.
 */
interface WorkoutTrimpBackfillStatus {
    /**
     * True when at least one workout starting at or after [retentionStartMs] has never been
     * evaluated (NULL `modelTrimp` and NULL `modelTrimpQuality`). Bounded by the retention window
     * so a resync that can never reach older rows is not re-enqueued on every launch.
     */
    suspend fun hasUnbackfilledWorkouts(retentionStartMs: Long): Boolean
}
