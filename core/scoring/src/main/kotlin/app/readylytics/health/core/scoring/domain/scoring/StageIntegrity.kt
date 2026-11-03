package app.readylytics.health.core.scoring.domain.scoring

/**
 * Stage-integrity predicates shared by night validation. Kept pure so `validateNight` is the single
 * source of truth for `stagesSuspicious`.
 */
object StageIntegrity {
    /**
     * True when a night has recorded duration but neither Deep nor REM minutes -- the signature of
     * devices that only emit a generic SLEEPING stage (or light/awake only). Architecture cannot be
     * scored from such a night, so it is treated as suspicious and reweighted.
     */
    fun hasNoStageBreakdown(
        durationMinutes: Int,
        deepMinutes: Int,
        remMinutes: Int,
    ): Boolean = durationMinutes > 0 && deepMinutes == 0 && remMinutes == 0
}
